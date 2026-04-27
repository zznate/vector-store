# vector-store-storage

Object-store integration for vector-store: the AWS SDK v2 `S3Client`
producer, a JVector-compatible `RandomAccessReader` that serves ranged
`GetObject` requests, an on-heap block cache that dedupes cold bytes across
segments, and the `S3SegmentStore` that glues them together. See the
[repo root README](../README.md) and
[`docs/design-notes.md`](../docs/design-notes.md) for the object-store
layout and Phase 1 latency target.

## Role

Owns everything about talking to an S3-compatible object store (MinIO in
dev, real S3 in prod). Every segment built by
[`vector-store-engine`](../vector-store-engine/README.md) lands here on
commit; every query goes through this module when reading segment bytes.
The object store itself is an implementation detail — the consumer-facing
interface,
[`core.segment.SegmentStore`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/segment/SegmentStore.java),
is stable across the phase-2 local-filesystem and phase-3 S3 backends.

## Public surface

Packages under `io.github.zznate.vectorstore.storage`:

- [`config/`](src/main/java/io/github/zznate/vectorstore/storage/config) —
  `StorageConfig` (`@ConfigMapping(prefix="vectorstore.storage")`) binds the
  endpoint, region, bucket, credentials, path-style-access toggle, and the
  nested `block-cache.{bytes, block-size, l2.{enabled, bytes}}`.
- [`S3ClientProducer`](src/main/java/io/github/zznate/vectorstore/storage/S3ClientProducer.java)
  — `@Produces @Singleton S3Client` built from `StorageConfig`. Uses the
  URL-connection HTTP client (no Netty); 2 s connect timeout, 30 s read
  timeout.
- [`reader/`](src/main/java/io/github/zznate/vectorstore/storage/reader) —
  | Class | Role |
  |---|---|
  | `RangeReader` | Narrow seam (`readRange(offset, dst, dstOffset, length)`). Implemented by `S3RandomAccessReader` in prod and by test doubles in tests. |
  | `S3RandomAccessReader` | `RandomAccessReader` that issues a ranged `GetObject` per read. Emits `vectorstore.storage.range_get` spans, `vectorstore.storage.get.duration{cache_hit=false}`, `vectorstore.storage.get.bytes{direction=download}`. Stateful, **not thread-safe** (matches JVector's contract). |
  | `S3ReaderSupplier` | `ReaderSupplier` bound to a `(bucket, key)` pair. Probes the object length via `HeadObject` once at construction; each `get()` returns a fresh reader. |
  | `BlockCachingRandomAccessReader` | Decorator that serves reads out of the tiered block cache, falling back to an underlying `RangeReader` on miss. Emits `vectorstore.storage.get.duration{cache_hit=true}` on hits; the underlying tiers carry the `vectorstore.cache.{hit,miss,eviction}` counters tagged by `tier` and `cache_name=block`. |
- [`cache/`](src/main/java/io/github/zznate/vectorstore/storage/cache) —
  `BlockKey(objectKey, blockIndex)`, `BlockCache` (tiered facade: an
  on-heap `HeapCacheTier<BlockKey, byte[]>` with byte-weighted eviction
  and an optional off-heap `OffHeapArenaL2Provider` behind it), and
  `BlockCacheProducer`. Writes are write-through; cold L1 reads consult
  L2 and promote to L1 on hit.
- [`S3SegmentStore`](src/main/java/io/github/zznate/vectorstore/storage/S3SegmentStore.java)
  — implements `SegmentStore`. Uploads segment artefacts (`graph.jvec`,
  `ordinals.jsonl`, `header.json`, plus the empty `attributes.jsonl` and
  `tombstones.roar` placeholders) under
  `<bucket>/<objectPrefix>/...`. Files under 8 MiB go out as a single
  `PutObject`; larger files use manual multipart upload (8 MiB parts, abort
  on failure). `openGraph(Segment)` returns a `ReaderSupplier` that hands
  JVector a fresh block-cached reader per `get()` call.

## Block cache behaviour

Configuration keys, defaults, and tuning intent live under
`vectorstore.cache.block.*` — see the
[Cache configuration reference](../vector-store-core/README.md#cache-configuration-reference)
in `vector-store-core` for the canonical table. Implementation-specific
notes live here:

- **Block size matters.** JVector reads variably-sized chunks of the
  graph file; pick a block size that amortises per-request overhead
  without over-fetching on fine-grained reads. The 64 KiB default fits
  JVector's on-disk format well.
- **L2 is JDK-21 FFM-arena off-heap.** One arena per cached block;
  native memory is freed synchronously on eviction with no GC
  dependency. Enabling L2 requires `--enable-preview` on every JVM
  entry point (already wired in the parent POM).
- **L1↔L2 promotion on read.** A miss-on-L1 / hit-on-L2 promotes the
  block back to L1, so a cold block becomes hot after one successful
  off-heap read.
- **MINIMAL-policy indexes bypass L2.** Every per-segment reader for an
  index whose `IndexBuildParams.cachePolicy = MINIMAL` is constructed
  with `useL2=false`, so cold blocks for those indexes neither read
  from nor warm the off-heap tier. SMART (default) and RESIDENT indexes
  use both tiers.
- **Hit / miss ratios by tier** are on Prometheus via
  `vectorstore.cache.{hit,miss,eviction}{tier, cache_name=block}` —
  watch `tier=l1_heap` and `tier=l2_offheap` to see promotion behaviour.

## CDI wiring

`vector-store-app` produces a single `SegmentStore` bean by dispatching on
`vectorstore.segments.store`:

- `s3` (default) → `S3SegmentStore` from this module, with injected
  `S3Client`, `StorageConfig`, the block size from `CacheConfig`,
  `BlockCache`, `CachePolicyResolver`, `MeterRegistry`, and `Tracer`.
- `local` → phase-2 `LocalSegmentStore` from
  [`vector-store-engine`](../vector-store-engine/README.md) (for tests and
  developers without Docker).

`%test-local` profile flips the kind to `local`; anything else uses S3.

## Extension points for further multi-level caching

`BlockCache` is the tiered façade in front of L1 (heap) and the optional
L2 (off-heap arena). A future L3 disk tier (NVMe-backed) plugs in behind
the same `L2Provider` interface from
[`vector-store-core`](../vector-store-core/README.md), and `BlockCache`
already promotes-on-read so any new tier inherits the promotion path
without changing call sites. `BlockKey` intentionally encodes the full
`<bucket>/<key>` so one process-wide cache can front multiple buckets
without collision.

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) — catalog records.
- `io.github.jbellis:jvector` (`provided`) — `RandomAccessReader` /
  `ReaderSupplier` interfaces.
- `software.amazon.awssdk:s3` + `url-connection-client` — AWS SDK v2.
- `com.github.ben-manes.caffeine:caffeine` — block cache.
- `org.roaringbitmap:RoaringBitmap` — pinned here for phase-4 tombstones;
  unused in phase 3 itself.
- `io.micrometer:micrometer-core` + `io.opentelemetry:opentelemetry-api`
  (both `provided`) — meters and spans.

## Local development

- Unit tests: `./mvnw -pl vector-store-storage test`. Covers
  `S3RandomAccessReader` (range-header shape, endianness, meter emission),
  `S3ReaderSupplier` (HeadObject probe), and
  `BlockCachingRandomAccessReader` (block alignment, cross-block reads,
  byte-weighted eviction, tail blocks).
- Integration tests live in
  [`vector-store-app`](../vector-store-app/README.md) and bring up a MinIO
  container via Testcontainers, so they run as part of `./mvnw verify`
  alongside the rest of the reactor. No docker-compose needed for CI.

## Not in this module

- No JVector graph construction — that is
  [`vector-store-engine`](../vector-store-engine/README.md).
- No catalog SQL — that is
  [`vector-store-core`](../vector-store-core/README.md).
- No HTTP / REST — that is
  [`vector-store-api`](../vector-store-api/README.md).
- No Quarkus `application.properties` — that lives in
  [`vector-store-app`](../vector-store-app/README.md). This module only
  reads config via `@ConfigMapping`.
