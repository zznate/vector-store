# vector-store-api

## Purpose

Public REST surface of the vector-store service. Everything a client sees — URL
shapes, DTOs, error envelopes, API-key authentication — is defined in this
module. The module is JAX-RS against `provided` Jakarta APIs; the Quarkus
runtime in `vector-store-app` supplies the implementations.

Owns:

- Resource classes under `resource/` — one per top-level noun
  (`BucketsResource`, `IndexesResource`, `VectorsResource`).
- DTOs under `dto/` — all records, all with Bean Validation annotations.
- `VectorStoreException` hierarchy plus a single `@Provider` exception mapper
  in `error/`.
- API-key authentication under `auth/`:
  `ApiKeyAuthenticationFilter`, `BucketScopedPrincipal`, and
  `Argon2PasswordHasher` (the only implementation of `PasswordHasher`).

## Public contract

- The HTTP surface itself (see [`docs/design-notes.md`](../docs/design-notes.md)).
- The `PasswordHasher` interface for the app module's startup bootstrap bean.
- The `BucketScopedPrincipal` type visible on `SecurityContext.getUserPrincipal()`.

No non-resource class is intended for consumption by siblings other than
`vector-store-app`.

## Dependencies

- `vector-store-core` for the catalog records and repository interfaces.
- Jakarta REST, Bean Validation, CDI, MicroProfile OpenAPI — all `provided`.
- `de.mkammerer:argon2-jvm-nolibs` — the password hashing choice.

No dependency on `vector-store-engine`, `vector-store-storage`, or
`vector-store-metadata`.

### Password hashing: Argon2id via `argon2-jvm-nolibs`

We chose Argon2id over BCrypt because:

1. Argon2 is OWASP's first-choice password KDF in 2025+ guidance.
2. The `-nolibs` artifact is pure Java — no JNI, no native dependencies, so
   containerisation and test isolation are trivial.
3. Apache License 2.0, actively maintained.

Defaults: memory cost 65536 KiB, iterations 3, parallelism 1, salt 16 bytes.
Override via `Argon2PasswordHasher` constructor parameters if needed.

## Local development

- Unit-test this module on its own: `./mvnw -pl vector-store-api test`. Those
  tests cover hashing and filter scope logic against a fake
  `ApiKeyRepository`.
- End-to-end resource behaviour is covered by `@QuarkusTest` suites in
  `vector-store-app`, which boot the full Quarkus runtime.

## Not in this module

- No JDBI, no SQL. SQL lives in `vector-store-core`.
- No vector / index engine work. That belongs in `vector-store-engine`.
