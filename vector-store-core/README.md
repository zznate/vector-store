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
