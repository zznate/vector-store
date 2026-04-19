# vector-store-app

Quarkus runtime for vector-store: configuration, CDI wiring, startup
bootstrapping, Micrometer meter registration, and the `@QuarkusMain`
entrypoint. See the [repo root README](../README.md) for the project
overview and [`docs/design-notes.md`](../docs/design-notes.md) for the
observability plan.

## Role

The only module with `application.properties` and the only one that depends
on every sibling. Everything else in the project is framework-light by
design; this module bolts it all onto the Quarkus runtime.

## Public surface

Nothing outside the module consumes its classes. What lives here:

- [`application.properties`](src/main/resources/application.properties) —
  profile-aware Quarkus configuration (datasource, Flyway, OTel, logging,
  OpenAPI, Prometheus scrape path, Argon2 params).
- [`VectorStoreApplication`](src/main/java/io/github/zznate/vectorstore/app/VectorStoreApplication.java) —
  explicit `@QuarkusMain` entrypoint.
- [`config/`](src/main/java/io/github/zznate/vectorstore/app/config) — CDI
  producers: `Clock`, `Jdbi` (via
  [`JdbiConfigurer`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/catalog/jdbi/JdbiConfigurer.java)),
  the five repository beans, and the `PasswordHasher` built from the
  `vectorstore.auth.argon2.*` config block.
- [`startup/AdminKeyBootstrap`](src/main/java/io/github/zznate/vectorstore/app/startup/AdminKeyBootstrap.java) —
  `@Observes StartupEvent`, reads `VECTORSTORE_BOOTSTRAP_ADMIN_KEY`, seeds
  an admin `ApiKey` iff none exists.
- [`metrics/MetricNames`](src/main/java/io/github/zznate/vectorstore/app/metrics/MetricNames.java) —
  constants for every meter in `docs/design-notes.md`.
- [`metrics/VectorStoreMeters`](src/main/java/io/github/zznate/vectorstore/app/metrics/VectorStoreMeters.java) —
  eagerly registers each of those meters at startup so `/q/metrics` is
  stable from boot.

`quarkus.index-dependency` entries in `application.properties` tell the
Quarkus build-time indexer to scan `vector-store-core` and `vector-store-api`
for `@Path`, `@Provider`, and CDI beans (sibling JARs are not indexed by
default).

## Dependencies

Depends on every sibling:
[`-core`](../vector-store-core/README.md),
[`-api`](../vector-store-api/README.md),
[`-engine`](../vector-store-engine/README.md),
[`-storage`](../vector-store-storage/README.md),
[`-metadata`](../vector-store-metadata/README.md).
Plus every Quarkus extension the service uses — `quarkus-rest`,
`-rest-jackson`, `-hibernate-validator`, `-smallrye-health`,
`-smallrye-openapi`, `-micrometer`, `-micrometer-registry-prometheus`,
`-opentelemetry`, `-logging-json`, `-agroal`, `-flyway`, and
`io.quarkiverse.jdbc:quarkus-jdbc-sqlite`.

## Runtime configuration

Quarkus maps SmallRye Config keys to `UPPER_SNAKE_CASE` env vars
automatically. The table lists the env var form and — where an equivalent
Quarkus config key exists — the dotted key to use with `-D...` system
properties.

| Variable | Quarkus config key | Default |
|---|---|---|
| `VECTORSTORE_DB_PATH` | interpolated into `quarkus.datasource.jdbc.url` | `./vector-store.db` |
| `VECTORSTORE_BOOTSTRAP_ADMIN_KEY` | `vectorstore.bootstrap.admin-key` | unset |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry SDK standard | `http://localhost:4317` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | OpenTelemetry SDK standard | `grpc` |
| `VECTORSTORE_AUTH_ARGON2_ITERATIONS` | `vectorstore.auth.argon2.iterations` | `3` |
| `VECTORSTORE_AUTH_ARGON2_MEMORY_KIB` | `vectorstore.auth.argon2.memory-kib` | `65536` |
| `VECTORSTORE_AUTH_ARGON2_PARALLELISM` | `vectorstore.auth.argon2.parallelism` | `1` |

### Profiles

- `%dev` — OTel SDK disabled, console (non-JSON) logging.
- `%test` — OTel SDK disabled, cheap Argon2 params, test SQLite path.
- any other profile (including `prod`) — OTel SDK enabled, JSON logging.

### OpenTelemetry endpoint

Endpoint + protocol come from the OpenTelemetry-spec environment variables
above — not from profile-prefixed properties. SmallRye Config gives
profile-prefixed keys in `application.properties` precedence over
plain-keyed system properties, which makes `%prod.*` overrides effectively
unreachable at deploy time. Keeping deployment facts out of the properties
file entirely is both 12-factor-correct and matches the OTel SDK spec.

### Bootstrap admin key

Set `VECTORSTORE_BOOTSTRAP_ADMIN_KEY` to a full `keyId.secret` token — the
same form clients send in `X-Api-Key`. On startup, if no admin key exists
in the catalog (`bucket_id IS NULL`), the secret is hashed with Argon2id
and a new admin `ApiKey` is created. Otherwise the variable is ignored.
Bootstrap is idempotent; do not rely on it for key rotation.

## Local development

```
./mvnw -pl vector-store-app quarkus:dev         # dev mode, live reload
./mvnw -pl vector-store-app verify              # @QuarkusTest component suite
```

Live reload picks up edits in sibling modules (core, api, …) automatically.

## Tests

Component tests under
[`src/test/java/io/github/zznate/vectorstore/app`](src/test/java/io/github/zznate/vectorstore/app)
use `@QuarkusTest` + RestAssured to exercise the HTTP surface end-to-end:

- `resource/AbstractResourceTest` truncates every catalog table before each
  test method and seeds an admin key plus two bucket-scoped keys so each
  test starts from a known state. The full suite shares one Quarkus boot
  and one SQLite file (under `target/`) for speed.
- `resource/BucketsResourceTest`, `IndexesResourceTest`, `VectorsResourceTest`
  cover the full `/v1` surface including 401 / 403 / 404 / 409 / 501
  response paths.
- `system/HealthAndMetricsTest` verifies `/q/health`, `/q/metrics`,
  `/q/openapi` are reachable without authentication and that every
  eagerly-registered meter appears in the Prometheus scrape.

`AbstractResourceTest` disables RestAssured's client-side URL encoding
(`RestAssured.urlEncodingEnabled = false`) because RestAssured percent-
encodes `:` in paths, which Quarkus REST does not decode before route
matching (reserved sub-delim per RFC 3986). Real clients — curl, browsers,
most HTTP libraries — send `:` unencoded.

## Not in this module

- No catalog SQL — see
  [`vector-store-core`](../vector-store-core/README.md).
- No REST resources — see
  [`vector-store-api`](../vector-store-api/README.md).
- No JVector / S3 / filter code — those arrive in later prompts in their
  own sibling modules.
