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
  sealed `VectorStoreException` hierarchy covering 401 / 403 / 404 / 409
  / 501, plus the single `@Provider VectorStoreExceptionMapper` that
  renders the `ErrorResponse`. Notable codes:

  | HTTP | `error` | Meaning |
  |---|---|---|
  | 401 | `unauthorized` | Missing or malformed `X-Api-Key`. |
  | 403 | `forbidden` | Bucket-scoped key against a different bucket, or non-admin key against an `@AdminOnly` route. |
  | 404 | `bucket_not_found` / `index_not_found` | Resource absent or in retention (deliberately indistinguishable from "never existed" so retention windows do not leak). |
  | 409 | `bucket_already_exists` / `index_already_exists` | Active row with the same id. |
  | 409 | `bucket_in_retention` / `index_in_retention` | Soft-deleted row with the same id is still inside retention. The error message includes `deleted_at`; client must wait for retention to expire or restore the row (see [restore endpoints](#restore-endpoints) once task #13 lands). |
  | 409 | `bucket_not_empty` | Cannot soft-delete a bucket with active child indexes. |

- [`auth/`](src/main/java/io/github/zznate/vectorstore/api/auth) —
  `PasswordHasher` (interface) and `Argon2PasswordHasher` (pure-Java
  Argon2id, constructor-tunable), `BucketScopedPrincipal` /
  `VectorStoreSecurityContext`, the `@AdminOnly` annotation, and the
  `ApiKeyAuthenticationFilter` (`@Provider`, `AUTHENTICATION` priority).

### Query knobs

`POST /v1/indexes/{bucket}/{index}/vectors:query` accepts the following
[`QueryRequest`](src/main/java/io/github/zznate/vectorstore/api/dto/QueryRequest.java)
fields:

| Field | Required | Bounds | Default | Notes |
|---|---|---|---|---|
| `vector` | yes | length ≥ 1 | — | Float array. Dimension must equal `vector_index.dimension`. |
| `topK` | yes | `[1, 1000]` | — | Number of hits to return. |
| `filter` | no | — | none | See [`FilterParser`](../vector-store-metadata/README.md#filter-semantics) for the supported grammar (`Equals`, `In`, `And`, `Or`, `Not`). |
| `rerankK` | no | `[1, 10000]` | `topK` | Width of JVector's reranking pool. Wider widens the search-time accuracy/cost trade-off; only meaningful once PQ is adopted (with the current `InlineVectors`-only feature set the candidate pool is already exact). |
| `threshold` | no | `≥ 0.0` | `0.0` | Minimum approximate similarity for a candidate to enter the rerank pool. Cosine and dot-product yield scores in `[0, 1]` after JVector normalisation; `0.0` is "no cut". |
| `rerankFloor` | no | `≥ 0.0` | `0.0` | Minimum exact similarity for a reranked hit to be returned. |

Per-query knobs flow through to JVector via
[`SearchTuning`](../vector-store-engine/src/main/java/io/github/zznate/vectorstore/engine/search/SearchTuning.java).
Per-process build defaults (the parameters set at index creation
time) live at `vectorstore.index.defaults.*` — see
[`vector-store-core`](../vector-store-core/README.md#index-configuration-reference).

### Lifecycle and soft-delete

`DELETE /v1/buckets/{bucket}` and `DELETE /v1/buckets/{bucket}/indexes/{index}`
are **soft-deletes**: the catalog row stays with `deleted_at` set, and
the resource becomes invisible to every read path (`GET` returns 404,
`listByBucket` filters it out, queries against the index id no longer
resolve). The same id cannot be re-created within the retention window
— the create endpoints return `409 bucket_in_retention` /
`409 index_in_retention` with `deleted_at` in the message.

After the configured retention window elapses, the in-process
[`RetentionSweep`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/retention/RetentionSweep.java)
hard-deletes the row and cascades to:

- segment + manifest_version catalog rows;
- staged-tombstone rows (FK `ON DELETE CASCADE`);
- object-storage prefixes via `SegmentStore.deletePrefix(...)`.

Order: object-store first, then catalog. A JVM crash between phases
leaves orphan files which the next sweep iteration re-deletes
idempotently — the inverse failure (catalog gone, files referenced)
cannot occur.

Bucket hard-delete waits for every child index (in any state) to be
hard-deleted first, so a child index that is restored inside the
retention window cannot orphan onto a dead bucket. Effective bucket
lifetime is therefore at least `max(bucket.window, index.window)`.

Index soft-delete also clears any pending staged tombstones, write
buffer entries, and commit-coordinator state for the index — a
soft-deleted index must not be reachable through any cached or queued
state. See [`IndexesResource.delete`](src/main/java/io/github/zznate/vectorstore/api/resource/IndexesResource.java)
for the full invalidation list.

The retention sweep is **disabled by default** (`vectorstore.retention.enabled=false`).
Operators must opt in explicitly. See [`vector-store-app`](../vector-store-app/README.md#retention-sweep)
for the full config reference.

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
