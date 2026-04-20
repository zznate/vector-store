# vector-store-api

Public REST surface of the vector-store service — DTOs, resources, exception
hierarchy, and API-key authentication. See the
[repo root README](../README.md) for the project overview and
[`docs/design-notes.md`](../docs/design-notes.md) for the authoritative
URL shape and observability plan.

## Role

Everything a client sees comes from this module: URL templates, request and
response shapes, the `{error, message}` envelope, and the `X-Api-Key` auth
flow. It is plain Jakarta EE against `provided` APIs; the Quarkus runtime in
[`vector-store-app`](../vector-store-app/README.md) supplies the
implementations and wires the CDI beans.

## Public surface

Organised under `io.github.zznate.vectorstore.api`:

- [`resource/`](src/main/java/io/github/zznate/vectorstore/api/resource) —
  JAX-RS endpoints.

  | Class | Path prefix | Status |
  |---|---|---|
  | `BucketsResource` | `/v1/buckets` — admin-only | Full CRUD (phase 1) |
  | `IndexesResource` | `/v1/buckets/{bucket}/indexes` | Full CRUD (phase 1) |
  | `VectorsResource` | `/v1/indexes/{bucket}/{index}` | Put, query, delete, get, stats (phase 2) |
  | `CommitResource` | `/v1/indexes/{bucket}/{index}:commit` | Real impl backed by `CommitCoordinator` (phase 2) |

  `CommitResource` lives separately because attaching `:commit` directly to
  the `{index}` path parameter on a class shared with `/vectors:put`-style
  siblings caused Quarkus REST's URI-template matcher to drop the route.
  Isolating it onto its own class-level template keeps routing
  deterministic.

- [`dto/`](src/main/java/io/github/zznate/vectorstore/api/dto) —
  request / response records with Bean Validation. `ErrorResponse` is the
  envelope every non-2xx response carries.

- [`error/`](src/main/java/io/github/zznate/vectorstore/api/error) —
  sealed `VectorStoreException` hierarchy with eight permitted subclasses
  covering 401 / 403 / 404 / 409 / 501, plus the single
  `@Provider VectorStoreExceptionMapper` that renders the `ErrorResponse`.

- [`auth/`](src/main/java/io/github/zznate/vectorstore/api/auth) —
  `PasswordHasher` (interface) and `Argon2PasswordHasher` (pure-Java
  Argon2id, constructor-tunable), `BucketScopedPrincipal` /
  `VectorStoreSecurityContext`, the `@AdminOnly` annotation, and the
  `ApiKeyAuthenticationFilter` (`@Provider`, `AUTHENTICATION` priority).

### Authentication

- Token format: `keyId.secret`, carried in `X-Api-Key`.
- Filter scope:
  - `/q/*` management endpoints are public (no auth).
  - Resources or methods annotated `@AdminOnly` require an admin key
    (`bucketId IS NULL`).
  - Anything else with a `{bucket}` path parameter accepts an admin key or a
    key scoped to the matched bucket.
- `last_used_at` is touched on every successful authentication via
  `ApiKeyRepository.touchLastUsed`.
- Password hashing is Argon2id via
  [`com.password4j:password4j`](https://github.com/Password4j/password4j) —
  pure Java, no JNI, Apache 2.0. Salt 16 bytes, output 32 bytes, version
  `0x13`. Memory / iterations / parallelism come from
  `vectorstore.auth.argon2.*` config keys (see
  [`vector-store-app`](../vector-store-app/README.md)).

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) for the catalog
  records and repository interfaces.
- Jakarta REST, Bean Validation, CDI, MicroProfile OpenAPI — all `provided`.
- `com.fasterxml.jackson.core:jackson-databind` (`provided`) for
  round-tripping the `engine_params` JSON blob.
- `com.password4j:password4j` — the hashing implementation.

No dependency on `vector-store-engine`, `-storage`, or `-metadata`.

## Local development

- Unit tests: `./mvnw -pl vector-store-api test`. They cover the hashing
  round-trip, the exception hierarchy's status / error-code invariants, and
  the filter's auth + scope logic against a fake `ApiKeyRepository`
  ([`InMemoryApiKeyRepository`](src/test/java/io/github/zznate/vectorstore/api/auth/fakes/InMemoryApiKeyRepository.java)).
- End-to-end resource behaviour is covered by the `@QuarkusTest` component
  tests in [`vector-store-app`](../vector-store-app/README.md), which boot
  the full Quarkus runtime.

## Not in this module

- No SQL or JDBI — those live in
  [`vector-store-core`](../vector-store-core/README.md).
- No index-engine code — that is
  [`vector-store-engine`](../vector-store-engine/README.md).
- No Quarkus bootstrap, `application.properties`, or CDI producers — those
  belong to [`vector-store-app`](../vector-store-app/README.md).
