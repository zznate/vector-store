# vector-store

A lightweight vector-database service. Its public surface is "create a bucket,
create an index, upsert vectors with attributes, query by similarity with filters,
delete." The index is powered by [JVector](https://github.com/datastax/jvector)
and segments are persisted on any S3-compatible object store (MinIO for local
development). It is **not** an S3 reimplementation and is **not** wire-compatible
with any vendor's vector API — the object store is an implementation detail.

## Phase plan

| Phase | Goal | Status |
|-------|------|--------|
| 1 | Cold-archive proof of concept. One immutable segment per commit. Explicit commit. Infrequent-query latency target (100–500 ms). | In progress |
| 2 | Warm-query tier: real multi-level caching, LSM-shaped compaction, concurrent writers, richer filter grammar. | Planned — the architecture does not preclude it |

Full design context lives in [`docs/design-notes.md`](docs/design-notes.md). If a
later change disagrees with `design-notes.md`, fix the change — the design notes
are authoritative.

## Tech stack

| Layer | Choice |
|-------|--------|
| Language / JDK | Java 21 LTS (Panama Vector API via `--add-modules=jdk.incubator.vector`) |
| Framework | Quarkus 3.33.1 LTS |
| Build | Maven 3.9+, multi-module |
| Index engine | JVector 4.0.0-rc.8 |
| Object storage | MinIO for local/dev, any S3-compatible service for prod (AWS SDK v2) |
| Catalog | SQLite + JDBI 3 + Flyway (Phase 1); Postgres-ready schema |
| Observability | OpenTelemetry via OTLP gRPC; Micrometer metrics; JSON logging |
| Testing | JUnit 5, AssertJ, Testcontainers, RestAssured |

Password hashing uses **Argon2id** via
[`com.password4j:password4j`](https://github.com/Password4j/password4j)
(pure-Java, no JNI).

## Modules

```
vector-store-parent/
├── vector-store-api         REST resources, DTOs, API-key auth filter, exception mapper
├── vector-store-core        Domain model + JDBI catalog repositories + Flyway migrations
├── vector-store-engine      JVector adapter (populated in prompt 02)
├── vector-store-storage     S3-backed reader and block cache (populated in prompt 03)
├── vector-store-metadata    Attribute sidecar + filter compiler (populated in prompt 04)
└── vector-store-app         Quarkus bootstrap, configuration, CDI wiring, main entrypoint
```

See each module's `README.md` for its contract.

## Requirements

- JDK **21** on `PATH` (the project ships a `.java-version` pin for
  [jenv](https://www.jenv.be/) users).
- Maven **3.9+** (the `./mvnw` wrapper is included).

## Build

```
./mvnw verify
```

Runs all unit and integration tests under Surefire and Failsafe. JVM flags for
the Panama Vector API are configured in the parent POM so JVector exercises its
SIMD code path during tests.

## Run locally

```
./mvnw -pl vector-store-app quarkus:dev
```

Endpoints:

- `GET  /q/health` — liveness and readiness
- `GET  /q/metrics` — Prometheus scrape
- `GET  /q/openapi` — OpenAPI spec for the `/v1` surface

### Bootstrap admin key

On first startup, if the environment variable `VECTORSTORE_BOOTSTRAP_ADMIN_KEY`
is set and no admin key exists in the catalog, the application seeds a single
admin API key from that value. Pass it via the `X-Api-Key` header on admin
requests:

```
export VECTORSTORE_BOOTSTRAP_ADMIN_KEY='admin-dev-key'
./mvnw -pl vector-store-app quarkus:dev
```

```
curl -H "X-Api-Key: admin-dev-key" \
     -H "Content-Type: application/json" \
     -d '{"bucketId":"demo","displayName":"Demo"}' \
     http://localhost:8080/v1/buckets
```

## Test

```
./mvnw test                 # unit tests only
./mvnw verify               # unit + integration
./mvnw -pl vector-store-core test   # per-module
```

Tests do not connect to any external service in Phase 1's bootstrap prompt.
MinIO-backed tests arrive in a later prompt via Testcontainers.

## Configuration

Every launch must include the Panama Vector API JDK flags. The parent POM sets
these for Surefire / Failsafe automatically. For container images and
production, pass them via `JAVA_TOOL_OPTIONS`:

```
JAVA_TOOL_OPTIONS="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
```

Profile-specific configuration lives in
`vector-store-app/src/main/resources/application.properties`:

- `%dev` — OTel exporter disabled, console logging.
- `%test` — OTel exporter disabled, in-memory SQLite.
- `%prod` — OTel exporter points to `http://otel-collector.stepflow-o11y:4317`, JSON logging.

Override the catalog file path with `VECTORSTORE_DB_PATH`
(default: `./vector-store.db`).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
