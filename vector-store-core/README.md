# vector-store-core

Domain records + catalog data-access for the vector-store service. See the
[repo root README](../README.md) for the project overview and
[`docs/design-notes.md`](../docs/design-notes.md) for the authoritative
catalog schema and invariants.

## Role

`core` is the foundation every other module builds on. It owns the persisted
shape of the world (records + SQL schema) and the data-access seam
(repository interfaces + JDBI implementations). It deliberately stays
framework-light — CDI annotations only — so it can be unit-tested in plain
JUnit without booting Quarkus.

`-api`, `-engine`, `-storage`, and `-metadata` each depend only on this
module. `core` depends on no sibling.

## Public surface

Every caller outside this module consumes one of:

- Record types in [`catalog.model`](src/main/java/io/github/zznate/vectorstore/core/catalog/model):
  `Bucket`, `VectorIndex`, `Segment`, `ManifestVersion`, `ApiKey`,
  plus the `DistanceMetric` and `SegmentState` enums. All immutable; only
  persisted fields, no derived state.
- Repository interfaces in [`catalog.repository`](src/main/java/io/github/zznate/vectorstore/core/catalog/repository):
  `BucketRepository`, `VectorIndexRepository`, `SegmentRepository`,
  `ManifestVersionRepository`, `ApiKeyRepository`.

JDBI-backed implementations live in
[`catalog.jdbi`](src/main/java/io/github/zznate/vectorstore/core/catalog/jdbi) —
package-private by convention, not intended for direct use. They are wired
into CDI by producers in
[`vector-store-app`](../vector-store-app/README.md).

`JdbiConfigurer.configure(Jdbi)` centralises the plugin + column-matcher
setup that both the production app and this module's test fixture apply, so
the mapping rules never drift apart.

## Dependencies

- `org.jdbi:jdbi3-core`, `jdbi3-sqlobject`, `jdbi3-jackson2` — data access.
- `org.flywaydb:flyway-core` — so the migration files travel with this
  module. Execution itself is driven by the `app` module's Quarkus
  configuration.
- `jakarta.enterprise.cdi-api` and `jakarta.inject-api` at `provided` scope
  — the runtime supplies the implementations.

No dependency on any sibling module.

## Local development

- Run this module's tests: `./mvnw -pl vector-store-core test`.
- The test fixture
  ([`CatalogTestFixture`](src/test/java/io/github/zznate/vectorstore/core/testsupport/CatalogTestFixture.java))
  builds a per-test-class **temp-file SQLite database**, runs Flyway, and
  exposes a configured `Jdbi`. File-backed rather than `:memory:` because
  SQLite's in-memory mode gives each JDBC connection its own database, and
  Flyway would migrate a different DB than the test's `Jdbi`.

## Not in this module

- No HTTP, REST, JAX-RS, or JSON marshalling — see
  [`vector-store-api`](../vector-store-api/README.md).
- No JVector, S3, filter compilation — those arrive in later prompts in the
  dedicated sibling modules.
- No Quarkus extensions beyond the CDI annotations above. Reaching for
  `@QuarkusTest` or `ConfigProperty` here is a sign the code belongs in
  [`vector-store-app`](../vector-store-app/README.md).
