# vector-store-core

Domain records + catalog data-access for the vector-store service. See the
[repo root README](../README.md) for the project overview and
[`docs/design-notes.md`](../docs/design-notes.md) for the authoritative
catalog schema and invariants.

## Role

`core` is the foundation every other module builds on. It owns the persisted
shape of the world (records + SQL schema) and the data-access seam
(repository interfaces + JDBI implementations). It deliberately stays
framework-light — CDI annotations only — so it can be unit-tested in plain
JUnit without booting Quarkus.

`-api`, `-engine`, `-storage`, and `-metadata` each depend only on this
module. `core` depends on no sibling.

## Public surface

Every caller outside this module consumes one of:

- Record types in [`catalog.model`](src/main/java/io/github/zznate/vectorstore/core/catalog/model):
  `Bucket`, `VectorIndex`, `Segment`, `ManifestVersion`, `ApiKey`,
  plus the `DistanceMetric` and `SegmentState` enums. All immutable; only
  persisted fields, no derived state.
- Repository interfaces in [`catalog.repository`](src/main/java/io/github/zznate/vectorstore/core/catalog/repository):
  `BucketRepository`, `VectorIndexRepository`, `SegmentRepository`,
  `ManifestVersionRepository`, `ApiKeyRepository`,
  `StagedTombstoneRepository`.
- Soft-delete + retention orchestration in [`retention`](src/main/java/io/github/zznate/vectorstore/core/retention):
  `RetentionConfig` (`@ConfigMapping`) and `RetentionSweep` (pure-Java
  cascade — no Quarkus dependency). The Quarkus `@Scheduled` binding
  lives in [`vector-store-app`](../vector-store-app/README.md#retention-sweep).
- Cache abstractions in [`cache`](src/main/java/io/github/zznate/vectorstore/core/cache):
  - `CacheTier<K, V>` / `HeapCacheTier` — Caffeine-backed L1, byte- or
    count-weighted, emits the standard
    `vectorstore.cache.{hit,miss,eviction,bytes.current,entries.current}`
    meters tagged `tier=l1_heap` and a caller-supplied `cache_name`.
  - `L2Provider` / `OffHeapArenaL2Provider` — JDK 21 FFM-arena off-heap
    tier, byte-budgeted with synchronous LRU eviction and arena close.
  - `CachePolicy` enum (`RESIDENT` / `SMART` / `MINIMAL`) +
    `CachePolicyResolver` (`@ApplicationScoped`) that reads the
    per-index policy from `vector_index.engine_params` via
    `IndexBuildParams.fromJson`.
  - `CacheConfig` — `@ConfigMapping(prefix="vectorstore.cache")` owning
    every warm-tier budget (block, sidecar, manifest, segment-handle).
  - `ManifestCache` (under `catalog.manifest`) wraps `ManifestResolver`
    with version-keyed L1 + a tiny TTL on `currentVersion`.

JDBI-backed implementations live in
[`catalog.jdbi`](src/main/java/io/github/zznate/vectorstore/core/catalog/jdbi) —
package-private by convention, not intended for direct use. They are wired
into CDI by producers in
[`vector-store-app`](../vector-store-app/README.md).

`JdbiConfigurer.configure(Jdbi)` centralises the plugin + column-matcher
setup that both the production app and this module's test fixture apply, so
the mapping rules never drift apart.

## Cache configuration reference

Every warm-tier cache is sized through a single `vectorstore.cache.*`
config tree, bound by
[`CacheConfig`](src/main/java/io/github/zznate/vectorstore/core/cache/CacheConfig.java).
This is the canonical reference; sibling READMEs link here rather than
re-list the keys.

### `vectorstore.cache.block.*` — object-store block cache

Owned by [`vector-store-storage`](../vector-store-storage/README.md). L1
heap (Caffeine, byte-weighted) plus an optional L2 off-heap arena tier.

| Key | Type | Default | Range / notes |
|---|---|---|---|
| `bytes` | `long` | `67108864` (64 MiB) | L1 byte budget. Raise when the warm working set exceeds the budget and the L1 hit rate drops; headroom is cheap, missing a block costs a ranged `GetObject`. |
| `block-size` | `int` | `65536` (64 KiB) | Fixed block size for caching and alignment. Tune to the graph stride: larger blocks amortise per-request overhead, smaller waste less when reads are fine-grained. |
| `l2.enabled` | `bool` | `false` | Enable the off-heap arena tier behind L1. Required `--enable-preview` flag is already on every JVM entry point. |
| `l2.bytes` | `long` | `268435456` (256 MiB) | L2 byte budget. Typically 4–8× the L1 budget so warm blocks survive longer. Ignored when `l2.enabled=false`. |

### `vectorstore.cache.sidecar.*` — per-segment metadata sidecar cache

Owned by [`vector-store-metadata`](../vector-store-metadata/README.md).
Holds parsed `attributes.jsonl` and `tombstones.roar` per segment.

| Key | Type | Default | Range / notes |
|---|---|---|---|
| `bytes` | `long` | `134217728` (128 MiB) | L1 byte budget across attribute + tombstone sidecars. Sidecars are much smaller than graph files; raise headroom liberally if the working set exceeds the budget. |

### `vectorstore.cache.manifest.*` — version-keyed manifest cache

Owned by `core` itself ([`ManifestCache`](src/main/java/io/github/zznate/vectorstore/core/catalog/manifest/ManifestCache.java)).
Caches active-segment lists keyed by `(indexId, version)` plus a tiny
TTL cache of `currentVersion(indexId)`.

| Key | Type | Default | Range / notes |
|---|---|---|---|
| `max-entries` | `int` | `64` | Count-weighted bound on cached manifests. Each entry is a small `List<Segment>`; keep ≥ active index count. |
| `version-ttl-nanos` | `long` | `100000000` (100 ms) | TTL on the `currentVersion` probe. Lower bounds the latency-to-visibility for a freshly-committed version; raise to amortise more catalog round-trips at the cost of staleness. |

### `vectorstore.cache.segment-handle.*` — loaded segment cache

Owned by [`vector-store-engine`](../vector-store-engine/README.md).
Holds the loaded JVector `OnDiskGraphIndex` and ordinal-to-user-id array
per segment so warm queries skip per-query reconstruction.

| Key | Type | Default | Range / notes |
|---|---|---|---|
| `max-entries` | `int` | `256` | Count-weighted LRU bound. RESIDENT-policy indexes pin handles outside this bound, so the budget only sizes the SMART / MINIMAL pool. |

### Per-index cache policy

Cache *behaviour* per index lives on `IndexBuildParams.cachePolicy`
(persisted in `vector_index.engine_params` JSON), not on this config
tree:

- `RESIDENT` — pin every active segment in the segment-handle cache;
  pinned handles are exempt from LRU eviction.
- `SMART` (default) — share the LRU budget across every index.
- `MINIMAL` — keep L1 only; the block cache bypasses L2 reads and writes
  for this index's segments.

See [`vector-store-engine`](../vector-store-engine/README.md) for the
full `IndexBuildParams` table and the `CachePolicyEnforcer` query-path
hook.

### Metrics surface

Every tier emits the same counter / gauge family, tagged by `tier`
(`l1_heap`, `l2_offheap`, future `l2_disk`) and `cache_name` (`block`,
`sidecar`, `manifest`, `segment_handle`):

```
vectorstore.cache.hit{tier, cache_name}
vectorstore.cache.miss{tier, cache_name}
vectorstore.cache.eviction{tier, cache_name}
vectorstore.cache.bytes.current{tier, cache_name}
vectorstore.cache.entries.current{tier, cache_name}
```

Per-index residency gauges:

```
vectorstore.cache.resident.bytes{index_id}
vectorstore.cache.resident.segments{index_id}
```

## Index configuration reference

Index parameters are resolved at the resource layer with three layers
of precedence (highest to lowest):

1. **Per-index overrides** — the `engineParams` map on the
   `POST /v1/buckets/{bucket}/indexes` request. Persisted to
   `vector_index.engine_params` as the canonical merged JSON; all
   later reads use this verbatim. Once an index has segments these
   values **freeze for the lifetime of the index** — JVector PR #659's
   `validateGraphConfiguration` requires every segment within one
   compaction to share `m`, `addHierarchy`, and the feature set.
2. **Per-process defaults** — `vectorstore.index.defaults.*` config
   keys ([`IndexBuildParamsDefaults`](src/main/java/io/github/zznate/vectorstore/core/catalog/model/IndexBuildParamsDefaults.java)).
   SmallRye Config layers env vars (`VECTORSTORE_INDEX_DEFAULTS_M=64`)
   over `application.properties` over `@WithDefault`. Applied only at
   index creation time; existing indexes are unaffected by changes.
3. **Per-query knobs** — `rerankK` / `threshold` / `rerankFloor` on
   the query DTO. See [`vector-store-api`](../vector-store-api/README.md#query-knobs)
   for the wire shape.

### `vectorstore.index.defaults.*` — per-process build defaults

| Key | Type | Default | Notes |
|---|---|---|---|
| `m` | `int` | `32` | Graph degree. Higher → better recall, more memory per node. |
| `beam-width` | `int` | `200` | Vamana ef_construction. Higher → better recall, slower build. |
| `neighbor-overflow` | `float` | `1.2` | Multiplier on the neighbour pool during insertion. |
| `alpha` | `float` | `1.2` | Vamana pruning threshold. |
| `pq-subspaces` | `int` | `128` | PQ subspaces. Must divide the vector dimension. Unused while `InlineVectors` is the only writer feature. |
| `pq-subspace-clusters` | `int` | `256` | Cluster count per PQ subspace. |
| `add-hierarchy` | `bool` | `false` | `false` = flat Vamana / DiskANN graph; `true` = HNSW-style hierarchy. |
| `cache-policy` | enum | `SMART` | Default warm-tier residency policy: `SMART` \| `RESIDENT` \| `MINIMAL`. Per-index override via `engineParams.cachePolicy`. |

`cacheBytes` is an opt-in per-index hint (`engineParams.cacheBytes`)
with no global default.

See [`vector-store-engine`](../vector-store-engine/README.md#jvector-parameters)
for tuning advice and the parameter sweep harness that evaluates
corners of this state space against the recall fixture.

### `vectorstore.index.startup-validation` — boot-time catalog check

| Key | Type | Default | Notes |
|---|---|---|---|
| `vectorstore.index.startup-validation` | enum | `warn` | `off` \| `warn` \| `error`. Controls [`IndexParamsStartupHook`](../vector-store-app/src/main/java/io/github/zznate/vectorstore/app/startup/IndexParamsStartupHook.java)'s response to drift / corruption found by [`IndexParamsValidator`](src/main/java/io/github/zznate/vectorstore/core/catalog/model/IndexParamsValidator.java). |

Modes:

- `off` — skip the scan. Fastest boot.
- `warn` — log INFO drift, log WARN parse failures, continue
  (default).
- `error` — log INFO drift, refuse startup with an
  `IllegalStateException` if any index's persisted `engine_params`
  fails to parse or violates the `IndexBuildParams` invariants.

Drift is normal once globals move (existing indexes keep their
persisted params); the INFO log is the operator's paper trail.
Parse failures usually signal a corrupted catalog row.

## Soft-delete + retention

`Bucket` and `VectorIndex` carry a nullable `deletedAt`. Active reads
filter `WHERE deleted_at IS NULL` at the DAO layer; `EXPLAIN QUERY
PLAN` confirms every filtered query uses an index for the primary
predicate (`bucket_id` / `index_id` PK or the `idx_vector_index_bucket`
secondary) and treats `deleted_at` as a residual filter. The `list*`
methods are the only full-table scans, and they are bounded by per-method
`LIMIT` plus documented caller invariants.

Repository surface beyond CRUD:

| Method | Caller | Notes |
|---|---|---|
| `findById` | REST read paths | Hides soft-deleted rows. |
| `findIncludingDeleted` | re-creation guard, restore, sweep | Returns any row regardless of state. |
| `softDelete(id, Instant)` | REST `DELETE` | Idempotent: returns `false` if already soft-deleted. |
| `restore(id)` | restore endpoint (task #13) | Returns `false` if absent or already active. |
| `hardDelete(id)` | retention sweep | Production REST never calls this. |
| `listSoftDeletedBefore(Instant)` | retention sweep | Filters by `deleted_at < cutoff`, ordered ASC. |
| `countAnyByBucket(bucketId)` (VectorIndex only) | retention sweep | Includes soft-deleted rows. Sweep gates bucket hard-delete on this returning 0. |

`VectorIndexRepository.deleteByIndex` and
`ManifestVersionRepository.deleteByIndex` are sweep-only batch removes
used during index hard-delete. `StagedTombstoneRepository.clearForIndex`
is invoked on index soft-delete (REST path) to drop pending tombstones —
a soft-deleted index must not be reachable through any cached or queued
state.

### `vectorstore.retention.*` — sweep configuration

Bound by [`RetentionConfig`](src/main/java/io/github/zznate/vectorstore/core/retention/RetentionConfig.java)
in this module; the scheduler binding + property comments live in
[`vector-store-app`](../vector-store-app/README.md#retention-sweep).
Disabled by default. See that page for the full table.

## Dependencies

- `org.jdbi:jdbi3-core`, `jdbi3-sqlobject`, `jdbi3-jackson2` — data access.
- `org.flywaydb:flyway-core` — so the migration files travel with this
  module. Execution itself is driven by the `app` module's Quarkus
  configuration.
- `com.github.ben-manes.caffeine:caffeine` — backs `HeapCacheTier`.
- `jakarta.enterprise.cdi-api` and `jakarta.inject-api` at `provided` scope
  — the runtime supplies the implementations.
- `io.smallrye.config:smallrye-config` at `provided` scope — annotations
  for `CacheConfig`'s `@ConfigMapping`; Quarkus supplies the runtime.
- `io.micrometer:micrometer-core` at `provided` scope — meters for
  `HeapCacheTier` / `OffHeapArenaL2Provider`.

No dependency on any sibling module.

## Local development

- Run this module's tests: `./mvnw -pl vector-store-core test`.
- The test fixture
  ([`CatalogTestFixture`](src/test/java/io/github/zznate/vectorstore/core/testsupport/CatalogTestFixture.java))
  builds a per-test-class **temp-file SQLite database**, runs Flyway, and
  exposes a configured `Jdbi`. File-backed rather than `:memory:` because
  SQLite's in-memory mode gives each JDBC connection its own database, and
  Flyway would migrate a different DB than the test's `Jdbi`.

## Not in this module

- No HTTP, REST, JAX-RS, or JSON marshalling — see
  [`vector-store-api`](../vector-store-api/README.md).
- No S3, filter compilation — those live in dedicated sibling modules.
  JVector types appear here only because the `SegmentStore` interface
  returns `RandomAccessReader` / `ReaderSupplier`; the implementations
  live in `engine` and `storage`.
- No segment-handle cache (`SegmentHandleCache` is in `engine` because
  it loads `OnDiskGraphIndex`).
- No block cache (`BlockCache` is in `storage` because it is bound to
  the S3 reader path).
- No `CachePolicyEnforcer` (lives in `engine` because it manipulates
  `SegmentHandleCache`).
- No Quarkus extensions beyond the CDI annotations above. Reaching for
  `@QuarkusTest` here is a sign the code belongs in
  [`vector-store-app`](../vector-store-app/README.md).
