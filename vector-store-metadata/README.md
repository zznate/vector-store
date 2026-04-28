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
  | `FilterCompiler` | `FilterExpr + OrdinalAttributes -> RoaringBitsAdapter`. Brute-force ordinal scan; a posting-list strategy will replace the scan when the per-segment posting-list sidecar is present, without changing the signature. Emits the `vectorstore.filter.compile.duration` histogram (tagged `index_id`, `term_count`, `result_ratio_bucket`) and the `vectorstore.filter.compile` span. |
  | `RoaringBitsAdapter` | Adapts a `RoaringBitmap` to JVector's `Bits` interface so the compiled mask feeds `GraphSearcher.search` directly. |

- [`sidecar/`](src/main/java/io/github/zznate/vectorstore/metadata/sidecar) —
  | Type | Role |
  |---|---|
  | `OrdinalAttributes` | Narrow read-only view (`size`, `attributesOf(int)`) the filter compiler depends on. Tests implement it directly; production code uses `AttributeSidecar`. |
  | `AttributeSidecar` | Parsed `attributes.jsonl` — a dense `List<Map<String, String>>` indexed by ordinal. Provides `parse(InputStream)` for the read path and `of(List)` for the write path (so the commit can cache the sidecar it just wrote without re-parsing). |
  | `AttributeSidecarWriter` | Serialises an in-memory ordinal list to the JSONL wire format (`{"ordinal":N,"attributes":{...}}` per line). |
  | `TombstoneSidecar` | `RoaringBitmap` wrapper with portable `read(InputStream)` / `toBytes()` + a pure `mergedWith(RoaringBitmap)` helper the commit path uses to union new ordinals with the existing persisted bitmap. |
  | `SidecarCache` | Process-wide Caffeine cache of parsed sidecars with byte-weighted eviction across attributes and tombstones (default 128 MiB total). |
  | `SidecarLoader` | Facade that returns parsed sidecars, loading from `SegmentStore.openSidecar` on miss and caching the result. Used by both `QueryCoordinator` (query path) and `CommitCoordinator` (which also calls `invalidate(segment)` after re-uploading tombstones so queries see the fresh bytes). |

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

- **Per-attribute posting lists** generated at segment build time. The
  `FilterCompiler` signature stays put; the implementation switches from
  ordinal scan to recursive bitmap operations over pre-built posting
  lists, narrowing candidates before graph search rather than filtering
  the search output. Closes the recall gap on restrictive filters
  documented in the engine module's filtered-recall integration test.
- **Range operators on numeric attributes** (`$gt`, `$lt`, `$gte`,
  `$lte`, `$between`). Requires the attribute type system below; new
  `FilterExpr.Range` sealed variant; rejected by the parser today.
- **Attribute type system** beyond strings. Requires a schema carried on
  the index or negotiated at write time.
- **Multi-tier sidecar cache**: an `L2Provider` behind the heap tier
  (the same shape the block cache already uses) when sidecar working
  sets routinely exceed the heap budget.

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
