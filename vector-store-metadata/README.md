# vector-store-metadata

Per-segment attribute sidecar, persisted tombstone bitmap, filter
compiler (equality, `$in`, `$or`, `$not`, implicit AND), and the shared
sidecar cache. See the [repo root README](../README.md) and
[`docs/design-notes.md`](../docs/design-notes.md) for the invariants
that "attributes live in a sidecar, not inside the graph file" and
"deletes are tombstone bits".

## Role

Owns the non-vector state attached to each segment. The engine writes
sidecars at commit time; the query coordinator reads them (via the
cache) to build per-segment accept masks. The JVector graph file and
object-store client stay unaware of any of this — the sidecar is the
integration seam for everything that is not "find me the nearest
neighbours" (filters, deletes, future phase-2 posting lists and attribute
type systems).

## Public surface

Packages under `io.github.zznate.vectorstore.metadata`:

- [`filter/`](src/main/java/io/github/zznate/vectorstore/metadata/filter) —
  | Type | Role |
  |---|---|
  | `FilterExpr` | Sealed AST: `Equals(key, value)`, `In(key, values)`, `And(terms)`, `Or(terms)`, `Not(term)`. |
  | `FilterParser` | Translates the wire-level `Map<String, Object>` from `QueryRequest.filter` into a `FilterExpr`. Recognises top-level `$or` (sole key), top-level `$not` (may sit alongside siblings), and `$in` as a leaf operator on a key. Multiple sibling keys imply implicit AND. Rejects non-string values, range operators, and unknown operators. |
  | `UnsupportedFilterOperatorException` | Plain-Java exception carrying the offending key and operator. The API layer wraps it in a `400 unsupported_operator` response. |
  | `AmbiguousFilterException` | Plain-Java exception raised when an operator's precedence is ambiguous given its siblings (today: top-level `$or` mixed with sibling keys). The API layer wraps it in a `400 bad_request` response. |
  | `FilterCompiler` | `FilterExpr + OrdinalAttributes (+ optional PostingListReader) -> RoaringBitsAdapter`. Rule-based planner picks the posting-list strategy when every leaf key is indexed and falls back to a brute-force ordinal scan otherwise. Emits the `vectorstore.filter.compile.duration` histogram (tagged `index_id`, `term_count`, `result_ratio_bucket`, `strategy`), the `vectorstore.filter.compile` span, and the `vectorstore.filter.strategy` counter. |
  | `RoaringBitsAdapter` | Adapts a `RoaringBitmap` to JVector's `Bits` interface so the compiled mask feeds `GraphSearcher.search` directly. |

- [`sidecar/`](src/main/java/io/github/zznate/vectorstore/metadata/sidecar) —
  | Type | Role |
  |---|---|
  | `OrdinalAttributes` | Narrow read-only view (`size`, `attributesOf(int)`) the filter compiler depends on. Tests implement it directly; production code uses `AttributeSidecar`. |
  | `AttributeSidecar` | Parsed `attributes.jsonl` — a dense `List<Map<String, String>>` indexed by ordinal. Provides `parse(InputStream)` for the read path and `of(List)` for the write path (so the commit can cache the sidecar it just wrote without re-parsing). |
  | `AttributeSidecarWriter` | Serialises an in-memory ordinal list to the JSONL wire format (`{"ordinal":N,"attributes":{...}}` per line). |
  | `TombstoneSidecar` | `RoaringBitmap` wrapper with portable `read(InputStream)` / `toBytes()` + a pure `mergedWith(RoaringBitmap)` helper the commit path uses to union new ordinals with the existing persisted bitmap. |
  | `SidecarCache` | Process-wide Caffeine cache of parsed sidecars with byte-weighted eviction across attributes, tombstones, and posting lists (default 128 MiB total). |
  | `SidecarLoader` | Facade that returns parsed sidecars (`attributes`, `tombstones`, `postings`), loading from `SegmentStore.openSidecar` on miss and caching the result. Used by both `QueryCoordinator` (query path) and `CommitCoordinator` (which calls `invalidate(segment)` after re-uploading sidecars so queries see the fresh bytes). |

- [`posting/`](src/main/java/io/github/zznate/vectorstore/metadata/posting) —
  | Type | Role |
  |---|---|
  | `PostingListConfig` | `@ConfigMapping` over `vectorstore.metadata.posting-list.*`. Today carries one knob: `max-cardinality` (default 10000). |
  | `PostingListFormat` | On-disk format constants for `postings.bin`: `PLST` magic, version, header layout, varint codec, FNV-1a 64-bit hash. |
  | `PostingListWriter` | Builds per-`(key, value)` `RoaringBitmap`s from the segment's attribute view, sorts by `(key_hash, value_hash)`, writes the header + index + string-pool + data layout. Returns `WriteResult(bytesWritten, skippedKeys)`; keys with more distinct values than the configured cap are skipped. |
  | `PostingListReader` | Loads `postings.bin` into memory, parses header + index eagerly, lazily deserialises bitmaps on `lookup(key, value)`. Implements `CachedSidecar` so the cache budget is shared. |

Cache budget is read from
[`CacheConfig.sidecar()`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/cache/CacheConfig.java)
in `vector-store-core` — see the [Sidecar cache sizing](#sidecar-cache-sizing)
section below for the property keys.

## Filter semantics

### Wire format

| Shape | AST |
|---|---|
| `{"k": "v"}` | `Equals("k", "v")` |
| `{"k1": "v1", "k2": "v2"}` | `And([Equals("k1","v1"), Equals("k2","v2")])` (implicit AND of sibling keys) |
| `{"k": {"$in": ["a","b"]}}` | `In("k", {"a","b"})` |
| `{"$or": [doc, doc, ...]}` | `Or([parse(doc), ...])` — must be the sole top-level key |
| `{"$not": doc, ...siblings}` | `Not(parse(doc))` — may mix with sibling equality keys |

### Rejection rules

- Numbers, booleans, arrays at a leaf, and `null` values raise `400 unsupported_operator`.
- Range operators (`$gt`, `$lt`, `$gte`, `$lte`, `$between`) and any other unrecognised `$`-prefixed operator raise `400 unsupported_operator` naming the offending operator.
- Top-level `$or` mixed with any sibling keys raises `400 bad_request` with the `bad_request` error code (ambiguous precedence — nest siblings inside the disjunction's clauses or remove them).
- `$in` may be combined with sibling keys (it is a leaf) but the operator envelope must contain `$in` alone — mixing `$in` with another leaf operator raises `400 unsupported_operator`.

### Other invariants

- Missing attributes never match `Equals` or `In` (there is no `IS NULL` operator). `Not(Equals(k, v))` therefore accepts ordinals that do not carry the key.
- A `null` / empty filter returns `null` from the parser and the compiler short-circuits to an "accept every ordinal" bitmap; with no tombstones the query coordinator further short-circuits to `Bits.ALL` so the common case allocates nothing.

## Posting-list sidecar

Each segment carries a `postings.bin` written at commit time alongside
the attribute and tombstone sidecars. It maps every indexed
`(key, value)` pair to a `RoaringBitmap` of accepting ordinals, so a
typical filter compile becomes a sequence of bitmap unions and
intersections instead of a per-ordinal attribute scan.

```
header (32 bytes)
  magic       4   "PLST"
  version     4
  term_count  4
  index_off   8
  data_off    8
  reserved    4

index block (term_count * 40 bytes, sorted by (key_hash, value_hash))
  key_hash    8       (FNV-1a 64-bit of the key string)
  value_hash  8       (FNV-1a 64-bit of the value string)
  key_off     4       (offset into the string pool)
  value_off   4
  data_off    8       (offset within the data block)
  data_len    8       (serialised RoaringBitmap byte length)

string-pool block      (varint-length-prefixed UTF-8)
data block             (concatenated portable RoaringBitmap bytes)
```

All multi-byte integers are big-endian, matching JVector's wire
convention. The string pool resolves any hash collisions on lookup.

### Strategy selection

`FilterCompiler` is a small rule-based planner:

| Filter shape | Strategy |
|---|---|
| Every leaf predicate (`Equals`, `In`) names a key in `PostingListReader.indexedKeys()` | **posting-list** — recursive bitmap `AND` / `OR` / `andNot` over the per-`(key, value)` lists; `Not(t)` is computed against the full `[0, vector_count)` ordinal range |
| Any leaf names a key with no posting list (or no posting-list sidecar is loaded) | **scan** — brute-force ordinal evaluation against the attribute view |
| `null` filter | accept-all short-circuit; no strategy chosen |

Cost-based selection (using per-key cardinality and selectivity hints)
is named in [`FUTURE.md`](FUTURE.md).

### High-cardinality fallback

`PostingListWriter` skips any key whose distinct-value count exceeds
`vectorstore.metadata.posting-list.max-cardinality` (default 10000).
Skipped keys come back on `WriteResult.skippedKeys()` and are
DEBUG-logged once per commit. Filters against a skipped key resolve to
the scan strategy at query time.

### Observability

- `vectorstore.filter.strategy{strategy=posting_list|scan, index_id}` —
  counter incremented once per compile.
- `vectorstore.filter.compile.duration{index_id, term_count,
  result_ratio_bucket, strategy}` — existing histogram, now broken
  down by strategy so the two paths' latency is comparable.
- `vectorstore.posting_list.size{index_id}` — distribution summary of
  per-segment `postings.bin` byte size, recorded once per commit.

## Sidecar cache behaviour

Configuration keys, defaults, and tuning intent live under
`vectorstore.cache.sidecar.*` — see the
[Cache configuration reference](../vector-store-core/README.md#cache-configuration-reference)
in `vector-store-core` for the canonical table. Implementation-specific
notes live here:

- **Byte-weighted, single tier.** Both attribute sidecars and tombstone
  bitmaps share one heap budget evicted byte-weighted LRU. There is no
  L2 tier yet; the working set must fit in L1.
- **Attribute sidecars dominate cache size** when workloads write many
  attributes per vector. Tombstones are always small unless a segment
  has been heavily deleted.
- **Sidecars are much smaller than graph files**, so headroom on the
  budget is cheap relative to the cost of a cold sidecar parse.
- **Cache metrics** flow through the shared `HeapCacheTier` and surface
  as `vectorstore.cache.{hit,miss,eviction}{tier=l1_heap, cache_name=sidecar}`
  on `/q/metrics`.

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) — `Segment`
  record and the `SegmentStore` interface used by `SidecarLoader`.
- `io.github.jbellis:jvector` (`provided`) — `Bits` interface.
- `org.roaringbitmap:RoaringBitmap` — mask storage and portable
  serialisation for the tombstone sidecar.
- `com.fasterxml.jackson.core:jackson-databind` (`provided`) — JSONL
  parsing and serialisation.
- `com.github.ben-manes.caffeine:caffeine` — cache implementation.
- `io.micrometer:micrometer-core` + `io.opentelemetry:opentelemetry-api`
  (both `provided`).

## Future work

See [`FUTURE.md`](FUTURE.md) for the deferred backlog: FST-based
posting-list index, per-key cardinality hint in the header, block-level
compression, range operators + typed attributes, cost-based strategy
selection, and the multi-tier sidecar cache.

## Local development

- Unit tests: `./mvnw -pl vector-store-metadata test`. Covers parser
  rejection paths, compiler correctness against a brute-force reference,
  sidecar round-trip, and cache eviction under byte pressure.
- Filter + tombstone behaviour end-to-end is exercised by the MinIO
  integration tests in [`vector-store-app`](../vector-store-app/README.md).

## Not in this module

- No JVector graph construction — see
  [`vector-store-engine`](../vector-store-engine/README.md).
- No object-store I/O — see
  [`vector-store-storage`](../vector-store-storage/README.md).
- No HTTP / REST — see
  [`vector-store-api`](../vector-store-api/README.md).
- No Quarkus `application.properties` — that lives in
  [`vector-store-app`](../vector-store-app/README.md). This module only
  reads config via `@ConfigMapping`.
