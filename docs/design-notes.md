# vector-store — Design Notes

Shared context for the implementation prompts. Treat this document as authoritative; individual prompts reference it rather than duplicating content.

## Project goal

Expose a vector-database API whose underlying index uses JVector and whose segment storage lives on any S3-compatible object store. Phase 1 targets infrequent-query workloads similar to a "cold archive": build an index, write it once to object storage, serve queries against it with acceptable (100–500ms) latency. The goal with this initial phase is effectively service-ifying JVector with a modern front-end (Quarkus) and implemeting S3-compatible file writers. 

Phase 2 will layer warm-query caching and LSM-shaped compaction on top of the same data model to offer more adaptable use cases beyond cold archive.

## Phase 1 invariants (must not regress)

These five invariants make Phase 2 additive rather than a breaking rewrite. Preserve them even when the simpler alternative is tempting.

1. **Index → Manifest → [Segment]**, always. Even when the segment list has one element. Clients never reference segment IDs directly; they always resolve through a manifest.
2. **User-id ↔ (segment_id, ordinal)** mapping stored per segment from day one. JVector uses dense ordinals per graph, but user-facing IDs must be stable across the life of an index.
3. **Query path is fan-out-and-merge** across the segment list, even over `N=1`. The top-k merge step exists even when it is trivial.
4. **Per-vector metadata lives in a per-segment sidecar file**, not inside the JVector file. Filter predicates are compiled into a `Bits` mask that the searcher accepts. Format and compiler evolve independently in Phase 2.
5. **Deletes are tombstone bits** in a per-segment sidecar, AND-ed into the query-time accept mask. Never rewrite the graph file for a delete.

## Tech stack

| Layer | Choice | Notes |
|-------|--------|-------|
| Language / JDK | Java 21 LTS | JVector auto-selects its Java 20+ (Panama Vector API) code path via multi-release JAR. Requires `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` at launch. Upgrade to JDK 25 is planned when Quarkus line we pin to explicitly lists 25 support. |
| Framework | Quarkus 3.x (latest LTS) | Pin to the current Quarkus LTS at repo-init time. |
| Build | Maven 3.9+, multi-module | |
| Index engine | JVector (multi-release JAR artifact) | M=32, beamWidth=200, PQ subspaces=128 as Phase 1 defaults. Per-index override planned. |
| Object storage | MinIO for local/dev, any S3-compatible service for prod | Service uses the AWS SDK v2 S3 client configured with a custom endpoint. |
| Catalog DB | SQLite (Phase 1), Postgres-ready | No ORM. JDBI 3 for data access. Flyway for migrations. Schema written in standard SQL; avoid SQLite-only constructs where practical. |
| Data access | JDBI 3 via the Quarkiverse `quarkus-jdbi` extension + Agroal datasource | |
| Schema migrations | Flyway via `quarkus-flyway` | |
| Observability | OpenTelemetry via `quarkus-opentelemetry` | OTLP gRPC exporter. Dev profile disables exporter and logs to console; prod profile exports to the cluster OTel Collector. |
| Testing | JUnit 5 + AssertJ + Testcontainers (MinIO container from phase 3) + RestAssured for REST | SQLite only for tests through phase 2; Postgres compatibility deferred. |

## Module layout

Single deployable. Modules are organized to isolate seams that Phase 2 will evolve.

```
vector-store-parent/                  # Maven parent POM
├── vector-store-api                  # REST resources, request/response DTOs, API-key auth filter
├── vector-store-core                 # Domain model (records/sealed types), catalog repository interfaces + JDBI implementations, manifest resolution
├── vector-store-engine               # JVector adapter: builder orchestration, on-disk writer, searcher
├── vector-store-storage              # Object-store client, S3 reader supplier, block cache
├── vector-store-metadata             # Attribute sidecar (JSON Lines in Phase 1), filter compiler producing Bits masks
└── vector-store-app                  # Quarkus bootstrap, datasource + OTel config, CDI wiring, main entrypoint
```

Module dependency rules:

- `storage` and `metadata` depend only on `core`.
- `engine` depends on `core` and on `metadata`. At commit time the
  `SegmentBuilder` serialises `attributes.jsonl` and the
  `CommitCoordinator` merges staged deletes into each segment's
  `tombstones.roar`; at query time the `QueryCoordinator` invokes the
  `FilterCompiler` and the `SidecarLoader`. The sidecar + filter types
  are metadata concerns; routing them through `core` would force every
  phase-2 grammar extension to ripple through an interface layer that
  exists only to appease the isolation rule.
- `api` depends on `core` and on `engine` (transitively, on `metadata`).
  The REST resources invoke the engine's coordinator surface (write
  buffer, commit pipeline, query fan-out, tombstone set) and translate
  `UnsupportedFilterOperatorException` from the metadata parser into
  the `400 unsupported_operator` response. `api` does not depend on
  `storage` directly.
- `app` depends on all other modules and owns runtime configuration.
- No other sibling-to-sibling module dependencies. No circular deps.
- Shared types live in `core`. Do not create a `commons` dumping ground.

## Public API shape

Resource-oriented REST with Google-AIP-style `:action` verbs for non-CRUD operations. Mount under `/v1`.

```
POST   /v1/buckets                              Create bucket
GET    /v1/buckets                              List buckets
GET    /v1/buckets/{bucket}                     Get bucket
DELETE /v1/buckets/{bucket}                     Delete bucket (empty only)

POST   /v1/buckets/{bucket}/indexes             Create index (dimension, metric, engine params)
GET    /v1/buckets/{bucket}/indexes             List indexes
GET    /v1/buckets/{bucket}/indexes/{index}     Get index
DELETE /v1/buckets/{bucket}/indexes/{index}     Delete index

POST   /v1/indexes/{index}/vectors:put          Upsert vectors into the current write buffer
POST   /v1/indexes/{index}/vectors:query        kNN query with optional filter
POST   /v1/indexes/{index}/vectors:delete       Mark vectors deleted (tombstone)
GET    /v1/indexes/{index}/vectors/{id}         Get a specific vector + attributes

POST   /v1/indexes/{index}:commit               Flush write buffer into a new segment
GET    /v1/indexes/{index}/stats                Stats: segment count, vector count, bytes, etc.
```

`{index}` accepts the fully-qualified `bucketId/indexId` form.

### Auth

Header: `X-Api-Key: <token>`. API keys live in the catalog, scoped to a bucket. A simple filter validates on every request. No IAM-style policies in Phase 1.

### DTOs (illustrative)

```java
public record PutVectorsRequest(List<VectorInput> vectors) {}
public record VectorInput(String id, float[] vector, Map<String, String> attributes) {}

public record QueryRequest(float[] vector, int topK, Map<String, String> filter) {}
public record QueryResponse(List<QueryHit> hits) {}
public record QueryHit(String id, float score, Map<String, String> attributes) {}
```

All DTOs are Java records with initial validation via Bean Validation annotations.

## Object-store layout

All segments for an index share a common prefix. Files are considered immutable and are thus never renamed or rewritten. 

```
s3://<bucket>/vectorstore/<bucket-id>/<index-id>/
  manifest.json                                         # lists active segment IDs + schema version
  segments/
    <segment-id>/
      graph.jvec                                        # OnDiskGraphIndex output
      ordinals.jsonl                                    # {ordinal, user_id} per line
      attributes.jsonl                                  # {ordinal, attrs:{}} per line
      tombstones.roar                                   # serialized RoaringBitmap of deleted ordinals
      header.json                                       # segment metadata (vector count, built_at, engine params)
```

The `manifest.json` at the index level lists active segments. Writes to it are serialized through the catalog; the S3 copy is a read-replica for recovery.

## Catalog schema (Phase 1, SQLite)

Written in standard-ish SQL. Migration files live at `vector-store-core/src/main/resources/db/migration/` as `V1__initial.sql`, etc. We've purposely kept things simple for portability and ease of understanding for this initial system. 

```sql
CREATE TABLE api_key (
  key_id        TEXT PRIMARY KEY,
  secret_hash   TEXT NOT NULL,
  bucket_id     TEXT,                  -- null = admin key
  created_at    TIMESTAMP NOT NULL,
  last_used_at  TIMESTAMP
);

CREATE TABLE vector_bucket (
  bucket_id     TEXT PRIMARY KEY,
  display_name  TEXT NOT NULL,
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE vector_index (
  index_id      TEXT PRIMARY KEY,
  bucket_id     TEXT NOT NULL REFERENCES vector_bucket(bucket_id),
  display_name  TEXT NOT NULL,
  dimension     INTEGER NOT NULL,
  metric        TEXT NOT NULL,          -- COSINE | EUCLIDEAN | DOT_PRODUCT
  engine_params TEXT NOT NULL,          -- JSON: {m, beamWidth, pqSubspaces, ...}
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE segment (
  segment_id    TEXT PRIMARY KEY,
  index_id      TEXT NOT NULL REFERENCES vector_index(index_id),
  state         TEXT NOT NULL,          -- BUILDING | ACTIVE | RETIRED
  vector_count  INTEGER NOT NULL,
  bytes         BIGINT NOT NULL,
  object_prefix TEXT NOT NULL,          -- S3 key prefix (from layout above)
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE manifest_version (
  index_id      TEXT NOT NULL REFERENCES vector_index(index_id),
  version       INTEGER NOT NULL,
  segment_ids   TEXT NOT NULL,          -- JSON array of active segments
  created_at    TIMESTAMP NOT NULL,
  PRIMARY KEY (index_id, version)
);

CREATE INDEX idx_segment_index ON segment(index_id);
```

Repository interfaces in `vector-store-core/catalog/repository/`. JDBI-backed implementations in `vector-store-core/catalog/jdbi/`. The **`app`** module owns the datasource bean and injects it into JDBI.

## JVector parameter defaults (Phase 1)

| Parameter | Value | Source |
|-----------|-------|--------|
| Vector dimension | 1024 | Targeting `granite-278m` embeddings |
| Distance metric | Cosine | |
| `M` (graph degree) | 32 | Matches JVector examples |
| `beamWidth` (ef_construction) | 200 | Higher than repo default of 100; build-once/query-many tradeoff |
| `neighborOverflow` | 1.2 | Repo default |
| `alpha` | 1.2 | Repo default |
| PQ subspaces | 128 | `dim/8` — JVector-idiomatic for 1024-d |
| Subspace cluster count | 256 (1 byte/subspace) | PQ default |
| Inline storage | `InlineVectors` Phase 1 (simpler); consider `FusedPQ` in Phase 2 | |

All configurable per-index via `engine_params` JSON at index-creation time. The listed defaults applied when omitted.

## Observability plan

Single OTLP gRPC exporter. Resource attributes:

- `service.name=vector-store`
- `service.version=<build-version>`
- `deployment.environment=<profile>` (added by the collector in cluster deployments; set locally in dev)

### Metrics (Micrometer bridged to OTel)

| Name | Type | Tags | Purpose |
|------|------|------|---------|
| `vectorstore.http.request.duration` | Histogram | method, route, status | Provided by Quarkus OTel instrumentation |
| `vectorstore.put.vectors` | Counter | index_id | Count of vectors accepted into write buffer |
| `vectorstore.commit.duration` | Histogram | index_id, phase (build, serialize, upload) | Commit cost breakdown |
| `vectorstore.commit.segment_bytes` | Histogram | index_id | Size of resulting segment |
| `vectorstore.query.duration` | Histogram | index_id, segment_count | Query wall time |
| `vectorstore.query.nodes_visited` | Histogram | index_id | Graph search cost |
| `vectorstore.storage.get.duration` | Histogram | cache_hit | Object-store GET latency; `cache_hit=true` on block-cache hits, `false` on ranged `GetObject` misses |
| `vectorstore.storage.get.bytes` | Counter | direction | Bytes transferred against the object store (`direction=download`) |
| `vectorstore.cache.hit` | Counter | `tier`, `cache_name` | Cache hits across every heap / off-heap / disk tier |
| `vectorstore.cache.miss` | Counter | `tier`, `cache_name` | Cache misses across every tier |
| `vectorstore.cache.eviction` | Counter | `tier`, `cache_name` | Entries evicted by the tier's bounding policy |
| `vectorstore.cache.bytes.current` | Gauge | `tier`, `cache_name` | Bytes currently held by the tier |
| `vectorstore.cache.entries.current` | Gauge | `tier`, `cache_name` | Entry count currently held by the tier |
| `vectorstore.filter.compile.duration` | Histogram | index_id, term_count, result_ratio_bucket | Filter compilation cost; `result_ratio_bucket` is one of `0-25`, `25-50`, `50-75`, `75-100` |
| `vectorstore.query.filtered_ratio` | DistributionSummary | index_id | Fraction of ordinals accepted per segment (scaled to 0–100) |

Tags to **never** include: user-supplied IDs, API keys, raw vector values, raw attribute values. `index_id` is an internal UUID, not user content.

### Traces

Quarkus auto-instruments HTTP, JDBI, and the Micrometer pipeline. Manual spans (names use dots, kind-appropriate):

- `vectorstore.commit.build` — graph build from write buffer
- `vectorstore.commit.serialize` — write graph to local tempdir
- `vectorstore.query.fanout` — parent span for per-segment fan-out
- `vectorstore.query.segment.search` — per-segment search, one child per active segment
- `vectorstore.storage.range_get` — ranged object GET, one per block-cache miss
- `vectorstore.cache.segment_handle.load` — cold-path load of a segment handle (opens graph, parses ordinals); attributes: `segment_id`, `index_id`
- `vectorstore.filter.compile` — filter predicate → Bits mask; attributes: `segment_id`, `index_id`, `term_count`, `vector_count`, `accepted_count`

Span attributes: `index_id`, `segment_id`, `top_k`, `vector_count`, `cache_hit`. Never raw vectors or user attributes.

### Logs

JSON via `quarkus-logging-json`. Trace/span IDs auto-correlated. Log events at INFO for state transitions (bucket/index/segment lifecycle), DEBUG for per-request internals. Never log vectors or attribute values.

### Profiles

- `%dev` — `quarkus.otel.sdk.disabled=true`, console logging (non-JSON for readability).
- `%test` — same as dev; tests assert on metrics via the Micrometer registry, not by exporting.
- `%test-local` — alias for developers running the app without Docker: overrides `vectorstore.segments.store=local` so the phase-2 `LocalSegmentStore` is produced and no MinIO is required.
- `%prod` — `quarkus.otel.exporter.otlp.endpoint=http://otel-collector.stepflow-o11y:4317`, JSON logging.

## Launch flags (every profile)

```
JAVA_TOOL_OPTIONS="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
```

Configure in:

- `vector-store-app/src/main/resources/application.properties` via `quarkus.native.additional-build-args` (for native builds) and `quarkus.args` where applicable
- Maven Surefire / Failsafe plugin config in `vector-store-parent/pom.xml` so tests exercise the SIMD path
- Container image `ENTRYPOINT` / `CMD`

## Testing strategy (Phase 1)

- **Unit tests** per module, covering pure logic (domain objects, filter compilation, catalog SQL via an in-memory SQLite). Target: every module has tests; coverage is a signal, not a gate.
- **Component / integration tests** in `vector-store-app` using `@QuarkusTest` and RestAssured. These drive the public REST surface end-to-end with real Quarkus wiring.
- **Testcontainers** for MinIO starting in phase 3. SQLite stays embedded in tests; Postgres compatibility is deferred.
- **Determinism**: tests that involve random vectors use a fixed seed. Any test that asserts recall must use brute-force ground truth on the same seeded dataset.

## Engineering conventions

- Records for DTOs and value types. Sealed interfaces for closed hierarchies.
- Immutability by default. Avoid shared mutable state outside clearly marked components (write buffer, caches).
- CDI for wiring. No static `Instance.of()`-style lookups in business code.
- Explicit error types: a small `VectorStoreException` hierarchy with HTTP status mapping in a single exception mapper.
- SQL lives in resource files or annotated JDBI SQL objects — not concatenated from strings in Java.
- No reflection-based mapping magic. Records or explicit mappers only.
- Every module ships a `README.md` (purpose, public contract, local dev, test strategy). Standard engineering format; no tool-specific conventions.
- Follow the existing commit style of the repo once initialized. Do not add co-author trailers referencing automated tools.
