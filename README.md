# vector-store

A lightweight vector-database service. The public surface is "create a bucket,
create an index, upsert vectors with attributes, query by similarity with
filters, delete." The index is powered by
[JVector](https://github.com/datastax/jvector) and segments are persisted on
any S3-compatible object store (MinIO for local development). It is **not** an
S3 reimplementation and is **not** wire-compatible with any vendor's vector
API — the object store is an implementation detail.

## Finding your way around

| Looking for | Read |
|---|---|
| The authoritative design (stack, schema, URL shape, observability) | [`docs/design-notes.md`](docs/design-notes.md) |
| What each module owns and depends on | each `vector-store-*/README.md` (linked below) |
| Build / run / test mechanics | the [Build](#build), [Run locally](#run-locally), and [Test](#test) sections below |
| How phases split up | the [Phase plan](#phase-plan) |
| Pre-PR checks, commit conventions, code style | [`CONTRIBUTING.md`](CONTRIBUTING.md) |

If something in the code disagrees with `docs/design-notes.md`, fix the code —
the design notes are authoritative.

## Phase plan

| Phase | Goal | Status |
|-------|------|--------|
| 1 | Cold-archive proof of concept. One immutable segment per commit. Explicit commit. Infrequent-query latency target 100–500 ms. | Landed |
| 2 | Warm-query tier: tiered block cache (L1 heap + optional L2 off-heap arena), bounded segment-handle cache, version-keyed manifest cache, per-index cache policy (`RESIDENT` / `SMART` / `MINIMAL`), durable staged tombstones, LSM-shaped compaction, concurrent writers, richer filter grammar. | In progress |

Phase 1 invariants (see `docs/design-notes.md` for the full list) must not
regress: Index→Manifest→[Segment] always, user-id↔(segment,ordinal) mapping
per segment, fan-out-and-merge on every query even at N=1, per-segment
attribute sidecar, tombstone bits AND-ed into the accept mask.

## Modules

`vector-store-core` has no internal dependencies. `vector-store-api`,
`-engine`, `-storage`, and `-metadata` each depend only on `core`.
`vector-store-app` depends on all five and owns the Quarkus runtime wiring as the exposed HTTP service.

| Module | Role | Status |
|---|---|---|
| [`vector-store-core`](vector-store-core/README.md) | Domain records, repository interfaces, JDBI implementations, Flyway migrations | Phase 1 populated |
| [`vector-store-api`](vector-store-api/README.md) | REST resources, DTOs, API-key auth filter, exception mapper | Phase 1 populated |
| [`vector-store-engine`](vector-store-engine/README.md) | JVector adapter: write buffer, segment builder, local-disk `SegmentStore`, searcher, commit + query coordinators | Phase 2 populated |
| [`vector-store-storage`](vector-store-storage/README.md) | S3 client wiring, ranged-GET reader, block cache | Phase 3 populated |
| [`vector-store-metadata`](vector-store-metadata/README.md) | Per-segment attribute sidecar, persisted tombstones, equality-filter compiler | Phase 4 populated |
| [`vector-store-app`](vector-store-app/README.md) | Quarkus bootstrap, CDI producers, startup seeding, main entrypoint | Phase 1 populated |
| [`vector-store-datagen`](vector-store-datagen/README.md) | Offline tooling: recall-fixture generation, demo-data seeding | Outside the service module graph; never run by CI |

## Tech stack

| Layer | Choice |
|-------|--------|
| Language / JDK | Java 21 LTS (Panama Vector API via `--add-modules=jdk.incubator.vector`; FFM Arena via `--enable-preview` until JDK 22) |
| Framework | Quarkus 3.33.1 LTS |
| Build | Maven 3.9+, multi-module |
| Index engine | JVector 4.0.0-rc.8 |
| Object storage | MinIO for local/dev, any S3-compatible service for prod (AWS SDK v2) |
| Catalog | SQLite + JDBI 3 + Flyway (Phase 1); schema stays Postgres-portable |
| Auth | API-key with Argon2id hashes via [`password4j`](https://github.com/Password4j/password4j) (pure Java, no JNI) |
| Observability | OpenTelemetry OTLP gRPC; Micrometer meters exposed at `/q/metrics`; JSON logging in `%prod` |
| Testing | JUnit 5, AssertJ, Mockito, RestAssured (+ Testcontainers in later prompts) |

## Requirements

- JDK **21** on `PATH`. The project ships a `.java-version` pin for
  [jenv](https://www.jenv.be/) users; any other Java version manager is fine.
- Maven **3.9+**. The `./mvnw` wrapper is committed.

## Build

```
./mvnw verify
```

Runs every module's Surefire (unit + `@QuarkusTest` component) tests and
Failsafe (integration) tests. JVM flags for the Panama Vector API are set in
the parent POM so JVector exercises its SIMD code path during tests.

## Run locally

### 1. Start MinIO

Phase 3 persists every segment to an S3-compatible object store. A
`docker-compose.yml` at the repo root brings up a single-node MinIO and
auto-creates the `vectorstore` bucket on first start:

```
docker-compose up -d minio
docker-compose logs mc-init   # confirms "bucket ready: vectorstore"
```

The S3 API is on `http://localhost:9000`; the admin console on
`http://localhost:9001` (root credentials default to `minioadmin`).
Credentials and bucket name are overridable via `.env` — see the variable
list below.

If you want to work without Docker, launch the app under the `test-local`
profile to fall back to the phase-2 local-filesystem segment store:

```
./mvnw -pl vector-store-app quarkus:dev -Dquarkus.profile=test-local
```

### 2. Start the service

```
./mvnw -pl vector-store-app quarkus:dev
```

Management endpoints:

- `GET /q/health` — liveness and readiness
- `GET /q/metrics` — Prometheus scrape with the meters named in
  `docs/design-notes.md`
- `GET /q/openapi` — OpenAPI spec for the `/v1` surface

All `/v1/*` routes require a valid `X-Api-Key` header; `/q/*` endpoints do
not. The REST surface itself lives in `vector-store-api` — see that module's
README for the request / response shapes and error envelope.

### Bootstrap admin key

On first startup, if the environment variable `VECTORSTORE_BOOTSTRAP_ADMIN_KEY`
is set and no admin key exists in the catalog, the application seeds one from
its value. The variable must carry a full `keyId.secret` token — the same
form clients send in the `X-Api-Key` header:

```
export VECTORSTORE_BOOTSTRAP_ADMIN_KEY='admin-local.dev-secret'
./mvnw -pl vector-store-app quarkus:dev
```

```
curl -H "X-Api-Key: admin-local.dev-secret" \
     -H "Content-Type: application/json" \
     -d '{"bucketId":"demo","displayName":"Demo"}' \
     http://localhost:8080/v1/buckets
```

Bootstrap is idempotent — if an admin key already exists, the variable is
ignored. The secret is hashed with Argon2id before storage.

## Test

```
./mvnw verify                   # full test suite across all modules
./mvnw -pl vector-store-core test   # one module only
```

Unit tests and the default `%test` component tests run offline. The phase-3
MinIO-backed integration tests bring their own MinIO via Testcontainers, so
`./mvnw verify` continues to work without a running docker-compose stack —
the tests start and stop their own container.

## Configuration

Every launch must include the Panama Vector JDK flags. The parent POM sets
them for Surefire / Failsafe automatically. For container images and
production, pass them via `JAVA_TOOL_OPTIONS`:

```
JAVA_TOOL_OPTIONS="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED --enable-preview"
```

Runtime configuration lives in
[`vector-store-app/src/main/resources/application.properties`](vector-store-app/src/main/resources/application.properties):

- `%dev` / `%test` — OTel SDK disabled, human-readable console logging.
- any other profile — OTel SDK enabled; endpoint reads from the
  OpenTelemetry-standard environment variable `OTEL_EXPORTER_OTLP_ENDPOINT`
  (default `http://localhost:4317`). JSON logging is enabled by the app's
  log config.

Key environment variables (see
[`vector-store-app/README.md`](vector-store-app/README.md) for the full
reference):

| Variable | Purpose | Default |
|---|---|---|
| `VECTORSTORE_DB_PATH` | SQLite catalog file | `./vector-store.db` |
| `VECTORSTORE_BOOTSTRAP_ADMIN_KEY` | `keyId.secret` token to seed the first admin key | unset |
| `VECTORSTORE_STORAGE_ENDPOINT` | S3 endpoint URL | `http://localhost:9000` |
| `VECTORSTORE_STORAGE_REGION` | AWS region (required by the SDK; ignored by MinIO) | `us-east-1` |
| `VECTORSTORE_STORAGE_BUCKET` | Bucket holding every segment | `vectorstore` |
| `VECTORSTORE_STORAGE_ACCESS_KEY` | S3 access key | `minioadmin` |
| `VECTORSTORE_STORAGE_SECRET_KEY` | S3 secret key | `minioadmin` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP collector endpoint | `http://localhost:4317` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | OTLP protocol | `grpc` |
| `VECTORSTORE_RETENTION_ENABLED` | Master switch for the retention sweep that hard-deletes soft-deleted bucket / index rows after the configured window. Disabled by default — operators must opt in. | `false` |

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
