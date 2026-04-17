# vector-store-core

## Purpose

Domain model and the catalog data-access layer for vector-store. Everything in
this module is framework-light so it can be exercised in plain JUnit tests
without needing Quarkus runtime bootstrap.

Owns:

- Immutable record types for every catalog entity: `Bucket`, `VectorIndex`,
  `Segment`, `ManifestVersion`, `ApiKey`.
- Repository interfaces under `catalog.repository` — the abstraction the rest of
  the codebase consumes.
- JDBI 3 SQL Object implementations under `catalog.jdbi` — SQL lives in
  annotations on the SQL objects, not concatenated strings.
- Flyway migrations under `src/main/resources/db/migration/`. The catalog
  schema from `docs/design-notes.md` is authoritative — `V1__initial.sql`
  must match it.

## Public contract

The five repository interfaces:

- `BucketRepository`
- `VectorIndexRepository`
- `SegmentRepository`
- `ManifestVersionRepository`
- `ApiKeyRepository`

All callers outside this module go through these interfaces; nothing should
reach for the JDBI implementations directly.

The five record types are also public. They are immutable and include only
persisted fields — no transient or derived state.

## Dependencies

- `jakarta.enterprise.cdi-api` and `jakarta.inject-api` are `provided`. The
  Quarkus runtime in `vector-store-app` supplies them.
- `org.jdbi:jdbi3-core` and `jdbi3-sqlobject` — the data-access engine.
- `org.flywaydb:flyway-core` — so the migration files ship with the module even
  when Flyway execution itself is driven by the `app` module.

No dependency on any sibling module.

## Local development

- Run just this module's tests: `./mvnw -pl vector-store-core test`
- Tests use an in-memory SQLite URL (`jdbc:sqlite::memory:`) with Flyway
  migrations applied in setup. Each test class should obtain its own JDBI
  instance — SQLite's in-memory database is per-connection by default.

## Not in this module

- No HTTP, no REST, no JAX-RS.
- No JVector, no S3.
- No Quarkus extensions beyond the CDI annotations `provided` above. If you
  find yourself reaching for `@QuarkusTest` or `ConfigProperty` here, it
  belongs in `vector-store-app`.
