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
- `com.password4j:password4j` — the password hashing choice.

No dependency on `vector-store-engine`, `vector-store-storage`, or
`vector-store-metadata`.

### Password hashing: Argon2id via `password4j`

We chose Argon2id over BCrypt because Argon2 is OWASP's first-choice password
KDF in 2025+ guidance. The implementation library is
[`com.password4j:password4j`](https://github.com/Password4j/password4j)
because it is genuinely pure Java (no JNI), small (~300 KB), actively
maintained, and Apache 2.0 licensed.

An earlier pick of `de.mkammerer:argon2-jvm-nolibs` was abandoned: despite
the name, that artifact still requires an OS-installed `libargon2` on the
library path. `password4j` has no such requirement.

Defaults in `Argon2PasswordHasher`: output 32 bytes, salt 16 bytes, Argon2
version 0x13. Memory, iterations, and parallelism are constructor parameters
so tests run with cheap settings and production tunes per deployment.

## Local development

- Unit-test this module on its own: `./mvnw -pl vector-store-api test`. Those
  tests cover hashing and filter scope logic against a fake
  `ApiKeyRepository`.
- End-to-end resource behaviour is covered by `@QuarkusTest` suites in
  `vector-store-app`, which boot the full Quarkus runtime.

## Not in this module

- No JDBI, no SQL. SQL lives in `vector-store-core`.
- No vector / index engine work. That belongs in `vector-store-engine`.
