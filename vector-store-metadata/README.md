# vector-store-metadata

Per-segment attribute sidecar, persisted tombstone bitmap, equality-filter
compiler, and the shared sidecar cache. See the
[repo root README](../README.md) and
[`docs/design-notes.md`](../docs/design-notes.md) for the filter grammar
and the invariants that "attributes live in a sidecar, not inside the
graph file" and "deletes are tombstone bits".

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
  | `FilterExpr` | Sealed AST: `Equals(key, value)` and `And(List<FilterExpr>)`. Nothing else is expressible in phase 1. |
  | `FilterParser` | Translates the wire-level `Map<String, Object>` from `QueryRequest.filter` into a `FilterExpr`. Rejects non-string values with `UnsupportedFilterOperatorException`. |
  | `UnsupportedFilterOperatorException` | Plain-Java exception carrying the offending key and operator. The API layer wraps it in a `400 unsupported_operator` response. |
  | `FilterCompiler` | `FilterExpr + OrdinalAttributes -> RoaringBitsAdapter`. Brute-force ordinal scan in phase 1; a phase-2 reimplementation will swap to bitmap intersections over pre-built posting lists without touching the signature. Emits the `vectorstore.filter.compile.duration` histogram (tagged `index_id`, `term_count`, `result_ratio_bucket`) and the `vectorstore.filter.compile` span. |
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

- [`config/MetadataConfig`](src/main/java/io/github/zznate/vectorstore/metadata/config/MetadataConfig.java)
  — `@ConfigMapping(prefix="vectorstore.metadata")` exposing the sidecar
  cache size.

## Phase 1 filter semantics

- Values are strings on the wire. Numbers, booleans, arrays, and objects
  are all rejected at parse time.
- Nested `{"key": {"$op": ...}}` shapes surface as
  `unsupported_operator` errors naming the operator (`$in`, `$or`, range
  operators all land here). The rejection path is deliberately explicit
  so phase-2 grammar additions slot in as new parse branches rather than
  as a semantic change to the type.
- Missing attributes never match. There is no `IS NULL` operator.
- A `null` / empty filter returns `null` from the parser and the compiler
  short-circuits to an "accept every ordinal" bitmap; with no tombstones
  the query coordinator further short-circuits to `Bits.ALL` so the
  common case allocates nothing.

## Sidecar cache sizing

Defaults: 128 MiB total across every cached sidecar (both attributes and
tombstones), evicted byte-weighted LRU. Tuning pointers:

- **Raise `vectorstore.metadata.sidecar-cache.bytes`** if the working set
  of segments exceeds the budget (warm hit rate falls). Sidecars are much
  smaller than graph files, so the headroom is cheap.
- **Attribute sidecars** dominate cache size when workloads write many
  attributes per vector. Tombstones are always small unless a segment
  has been heavily deleted.
- Cache metrics are exposed via Caffeine's stats facility; wiring
  them onto Prometheus counters is a phase-2 item.

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

## Phase-2 extension plan

- **Per-attribute posting lists** generated at segment build time. The
  `FilterCompiler` interface stays put; the implementation switches from
  ordinal scan to `bitmap.and(postingList(key, value))`. Expected to
  close the recall gap on restrictive filters — the phase-4 integration
  test deliberately documents the floor its brute-force scan produces on
  random Gaussian vectors.
- **Richer grammar**: `$in`, `$or`, range operators on numeric
  attributes. New parse branches in `FilterParser`; `FilterExpr` gains
  new sealed variants.
- **Attribute type system** beyond strings. Requires a schema carried on
  the index or negotiated at write time.
- **Durable staged deletes** (WAL or catalog table) so uncommitted
  deletes survive restart. Today, in-memory staging is lost on restart.
- **Multi-tier sidecar cache** (local SSD, Redis) layered behind
  `SidecarCache` without changing the facade.

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
