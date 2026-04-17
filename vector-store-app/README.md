# vector-store-app

## Purpose

The Quarkus application module. Owns runtime configuration, CDI wiring between
modules, startup bootstrapping (admin-key seeding), Micrometer meter
registration, and the application entrypoint.

This is the only module that depends on every sibling; it is also the only
module where `application.properties` lives.

Owns:

- `application.properties` — profile-aware Quarkus configuration.
- `config/JdbiProducer` — produces a `Jdbi` instance bound to the Agroal
  `DataSource`, with the SQL Object plugin and all project row mappers
  installed.
- `config/RepositoryProducers` — produces each `*Repository` CDI bean, wiring
  the JDBI-backed implementation from `vector-store-core`.
- `startup/AdminKeyBootstrap` — reads `VECTORSTORE_BOOTSTRAP_ADMIN_KEY`
  on `@Startup` and, if no admin API key exists, seeds one.
- `metrics/VectorStoreMeters` — eagerly registers every Micrometer meter named
  in `docs/design-notes.md` so the registry is stable from boot.

## Public contract

No external contract. This is a runtime module.

## Dependencies

Depends on every sibling: `vector-store-core`, `vector-store-api`,
`vector-store-engine`, `vector-store-storage`, `vector-store-metadata`. Also
depends on every Quarkus extension the application uses.

## Local development

- Start in dev mode: `./mvnw -pl vector-store-app quarkus:dev`.
  - OTel exporter is disabled in `%dev`; traces / metrics stay in-process.
  - Flyway migrates the SQLite file at
    `${VECTORSTORE_DB_PATH:./vector-store.db}` on boot.
- Live reload works for all sibling modules; editing code in
  `vector-store-core` is picked up without a restart.
- `./mvnw -pl vector-store-app verify` runs component tests with
  `@QuarkusTest` and RestAssured.

### Bootstrap admin key

First start with `VECTORSTORE_BOOTSTRAP_ADMIN_KEY` set to seed an admin API
key if none exists. The secret is hashed with Argon2id before storage; the
environment variable is only read at startup and is not persisted elsewhere.

## Tests

Component tests use `@QuarkusTest` and RestAssured. Each test class sets a
unique `VECTORSTORE_DB_PATH` (or uses `jdbc:sqlite:file::memory:?cache=shared`
with a per-test name) so Flyway has a clean schema per suite.

## Not in this module

- No catalog SQL. That lives in `vector-store-core`.
- No REST resources. Those live in `vector-store-api`.
- No JVector / S3 / filter code. Those arrive in later prompts in their own
  sibling modules.
