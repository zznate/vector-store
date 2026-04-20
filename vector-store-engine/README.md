# vector-store-engine

JVector adapter for vector-store: the in-memory write buffer, the
segment builder + searcher, the commit coordinator, and the local-disk
implementation of `SegmentStore`. See the
[repo root README](../README.md) for the project overview and
[`docs/design-notes.md`](../docs/design-notes.md) for the authoritative
on-disk layout, parameter defaults, and observability catalogue.

## Role

Everything between the catalog and the JVector graph file lives in this
module. Requests flow through
[`vector-store-api`](../vector-store-api/README.md)'s resources, which
call into the coordinators defined here; the coordinators read the
catalog through
[`vector-store-core`](../vector-store-core/README.md)'s repositories and
drive JVector with the phase 2 default parameter set.

`vector-store-engine` depends only on `core`. It is consumed by `api`
(for the coordinators) and by `app` (for the bean wiring).

## Public surface

Organised under `io.github.zznate.vectorstore.engine`:

- [`buffer/`](src/main/java/io/github/zznate/vectorstore/engine/buffer) —
  per-index accumulator that holds vectors between commits.
  - `WriteBuffer` interface + `InMemoryWriteBuffer` impl
    (`@ApplicationScoped`).
  - `BufferEntry` record carrying `(userId, float[] vector, attributes)`.
  - `BufferSnapshot` — the immutable view the commit pipeline consumes.
- [`build/`](src/main/java/io/github/zznate/vectorstore/engine/build) —
  the JVector graph builder.
  - `SegmentBuilder` (`@ApplicationScoped`). Emits spans
    `vectorstore.commit.build` + `vectorstore.commit.serialize`, timers
    `vectorstore.commit.duration` (tagged by phase), and the distribution
    summary `vectorstore.commit.segment_bytes`.
  - `SegmentHeader` — the record serialised to `header.json`.
- [`store/`](src/main/java/io/github/zznate/vectorstore/engine/store) —
  the local-disk implementation of
  [`core.segment.SegmentStore`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/segment/SegmentStore.java).
  - `LocalSegmentStore` writes segment directories under
    `${VECTORSTORE_SEGMENTS_ROOT}/<bucket>/<index>/<segment>/` and caches
    a `SimpleMappedReader.Supplier` per segment so concurrent queries
    against the same segment share one memory mapping.
- [`search/`](src/main/java/io/github/zznate/vectorstore/engine/search) —
  the query path.
  - `Searcher` interface + `SegmentSearcher` impl (`@ApplicationScoped`).
    Loads each segment's `ordinals.jsonl` lazily and caches the
    ordinal→userId map in a `String[]` per segment.
  - `QueryCoordinator` (`@ApplicationScoped`). Resolves the active
    manifest, fans out across every segment, merges the per-segment
    hits into top-k via a bounded heap. The fan-out is not conditional
    on segment count — the code path is identical at N=1 and N=100.
  - `ScoredOrdinal` record — one hit: graph ordinal, user ID, score.
- [`tombstone/`](src/main/java/io/github/zznate/vectorstore/engine/tombstone) —
  per-index in-memory deletion set.
  - `InMemoryTombstones` (`@ApplicationScoped`). Lost on restart; the
    metadata module will persist them in phase 4.
- [`commit/`](src/main/java/io/github/zznate/vectorstore/engine/commit) —
  the end-to-end commit pipeline.
  - `CommitCoordinator` (`@ApplicationScoped`) orchestrates a single
    commit (see below).
  - `CommitOutcome` record — what the API layer gets back.
  - `CommitFailedException` + `EmptyCommitException` — checked error
    types the API layer translates to HTTP responses.

## Commit pipeline

`CommitCoordinator.commit(VectorIndex)` serialises a single commit for
one index (per-index `ReentrantLock` so two concurrent commits for the
same index never interleave; different indexes never contend).

1. **Snapshot** — `WriteBuffer.snapshotAndClear(indexId)` atomically
   takes the current buffer and resets it to empty. In-flight `put`
   calls that race the snapshot land in the fresh buffer.
2. **Audit row** — `SegmentRepository.create(BUILDING)` with a fresh
   UUIDv7 segment ID. The row exists before any disk work so failures
   are traceable.
3. **Build** — `SegmentBuilder.build` produces a temp directory
   containing `graph.jvec`, `ordinals.jsonl`, `header.json`, plus
   empty `attributes.jsonl` and `tombstones.roar` placeholders that
   lock the on-disk layout in. Failure increments
   `vectorstore.commit.failures{phase=build}`.
4. **Publish** — `SegmentStore.publish` moves the temp directory under
   the canonical object prefix and returns its URI. Failure increments
   `vectorstore.commit.failures{phase=publish}`.
5. **Activate** — `SegmentRepository.updateStateAndBytes` flips the row
   to `ACTIVE` with the real on-disk byte count, then a new
   `manifest_version` row appending the new segment ID to the active
   list is written. Failure increments
   `vectorstore.commit.failures{phase=catalog}`.

Every failure path leaves the segment row in `RETIRED` state rather than
deleting it, so the catalog retains an audit trail of every build
attempt.

## Query pipeline

`QueryCoordinator.query(indexId, vector, topK)`:

1. Open span `vectorstore.query.fanout`.
2. `ManifestResolver.activeSegments(indexId)` — may return empty (valid
   state for an index with no commits; returns empty hits).
3. Tombstone snapshot — `InMemoryTombstones.tombstonedIds(indexId)`.
4. For each active segment: `Searcher.buildAcceptMask` (excludes
   tombstoned ordinals), then `Searcher.search` inside
   `vectorstore.query.segment.search`. The searcher opens the graph
   via `SegmentStore.openGraph`, runs JVector's `GraphSearcher`, and
   translates the returned ordinals to user IDs via the cached
   ordinal map.
5. Merge — a bounded min-heap of size `topK` across every per-segment
   hit. Result list is sorted descending by score.
6. Record `vectorstore.query.duration` timer, tagged by `index_id`.

## JVector parameters

Defaults live in
[`IndexBuildParams.defaults()`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/catalog/model/IndexBuildParams.java)
and are applied per-index at `POST /v1/buckets/{bucket}/indexes` time —
the full canonical set is merged with the caller's overrides and
persisted as JSON in `vector_index.engine_params`.

| Parameter | Default | Effect |
|---|---|---|
| `m` | 32 | Graph degree. Higher → better recall, more memory per node, slightly slower build. |
| `beamWidth` | 200 | Candidate pool during construction (Vamana equivalent of HNSW `ef_construction`). Higher → better recall, slower build. |
| `neighborOverflow` | 1.2 | Multiplier on the neighbour pool during insertion. Larger values settle the graph into better neighbourhoods at build cost. |
| `alpha` | 1.2 | Vamana pruning threshold. Higher retains more long-range edges. |
| `pqSubspaces` | 128 | Product-quantisation subspaces. Must divide the vector dimension. Unused in phase 2 (`InlineVectors` stores the raw vectors). |
| `pqSubspaceClusters` | 256 | Cluster count per subspace. One byte per subspace index. |
| `addHierarchy` | `false` | `false` = flat Vamana / DiskANN graph; `true` = HNSW-style multi-layer graph. On-disk format is compatible either way. |

Every parameter is settable per-index at creation time. Tuning advice:

- Start with defaults. They hit 17/20 top-1 on the recall fixture.
- If recall drops: raise `beamWidth` first (100 → 300 → 500), then `m`.
- If build is slow: lower `beamWidth` before `m` (recall loss is smaller).
- If memory per segment matters: lower `m`.
- Switch `addHierarchy` to `true` if the index will be queried with
  extreme top-k values where HNSW's entry-point amortisation helps.

## Observability

Meters catalogued in
[`MetricNames`](../vector-store-app/src/main/java/io/github/zznate/vectorstore/app/metrics/MetricNames.java)
and eagerly registered at boot. The engine emits:

| Meter | Type | Tags | Emitted by |
|---|---|---|---|
| `vectorstore.commit.duration` | Timer | `phase` (`build` \| `serialize`) | `SegmentBuilder` |
| `vectorstore.commit.segment_bytes` | DistributionSummary | — | `SegmentBuilder` |
| `vectorstore.commit.failures` | Counter | `phase` (`build` \| `publish` \| `catalog`) | `CommitCoordinator` |
| `vectorstore.query.duration` | Timer | `index_id` | `QueryCoordinator` |
| `vectorstore.query.nodes_visited` | DistributionSummary | `index_id` | `SegmentSearcher` |

Spans: `vectorstore.commit.build`, `vectorstore.commit.serialize`,
`vectorstore.query.fanout`, `vectorstore.query.segment.search`.
`segment_id` and `index_id` attributes are attached where meaningful.

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) — catalog
  records, repositories, `SegmentStore` interface, `ManifestResolver`.
- `io.github.jbellis:jvector` (explicit; also transitive via core).
- `com.fasterxml.jackson.core:jackson-databind` +
  `jackson-datatype-jsr310` (both `provided`) — `ordinals.jsonl` +
  `header.json` serialisation.
- `io.micrometer:micrometer-core`, `io.opentelemetry:opentelemetry-api`
  (both `provided`) — observability APIs; runtime supplied by
  `vector-store-app`.

No dependency on `vector-store-api`, `vector-store-storage`, or
`vector-store-metadata`.

## Local development

```
./mvnw -pl vector-store-engine test
```

Three unit-test surfaces:

- `SegmentBuilderRecallTest` — loads the Wikipedia corpus fixture from
  `src/test/resources/recall/`, builds a segment at the default
  parameters, runs 20 labelled natural-language queries and asserts
  top-1 + top-5-majority quality thresholds. The fixture itself is
  produced offline by
  [`vector-store-datagen`](../vector-store-datagen/README.md); CI never
  regenerates it.
- `InMemoryWriteBufferConcurrencyTest` — 16 producers × 1000 appends
  against a single snapshotter; asserts no append is lost or duplicated.
- `QueryCoordinatorMergeTest` — Mockito-backed test that verifies merge
  ordering, size-1 manifests, and zero-segment manifests without
  standing up real segments.

## Not in this module

- No HTTP / REST — see
  [`vector-store-api`](../vector-store-api/README.md).
- No catalog SQL — see
  [`vector-store-core`](../vector-store-core/README.md).
- No object-store I/O. The S3-backed `SegmentStore` implementation
  arrives with
  [`vector-store-storage`](../vector-store-storage/README.md) in phase 3.
- No attribute sidecar reading, no persistent tombstones, no filter
  compilation. Those arrive with
  [`vector-store-metadata`](../vector-store-metadata/README.md) in
  phase 4. Phase 2 leaves the `attributes.jsonl` and `tombstones.roar`
  placeholder files empty.
- No block cache. The phase 2 `LocalSegmentStore` memory-maps the whole
  graph file; phase 3 introduces a ranged-GET block cache when S3 is
  the substrate.
