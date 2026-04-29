# vector-store-test-support

Shared test fixtures and loaders, packaged as a regular jar so sibling
modules consume it as a normal `<scope>test</scope>` dependency.

## Role

A single source of truth for cross-module test data. Today carries the
Wikipedia + MiniLM-L6-v2 recall fixture (184 chunks, 20 queries) and
its record types; future fixtures live alongside.

The choice to package as a regular jar (rather than a maven-jar-plugin
test-jar from a sibling module) keeps the dependency graph explicit:
consumers declare a normal Maven dependency and the IDE / Maven both
resolve it without test-jar gymnastics.

## Public surface

Package `io.github.zznate.vectorstore.testsupport.fixtures`:

| Type | Role |
|---|---|
| `RecallFixture` | Static loaders: `loadCorpus()`, `loadQueries()`. Reads JSONL from this module's `src/main/resources/recall/`. |
| `FixtureChunk` | One line of `corpus.jsonl`: `(id, articleSlug, ordinalInArticle, text, embedding)`. |
| `FixtureQuery` | One line of `queries.jsonl`: `(id, text, expectedArticleSlug, embedding)`. |

## Resources

- [`recall/corpus.jsonl`](src/main/resources/recall/corpus.jsonl)
- [`recall/queries.jsonl`](src/main/resources/recall/queries.jsonl)
- [`recall/README.md`](src/main/resources/recall/README.md) — provenance,
  embedding model, source articles, regeneration commands.

## Consumers

- `vector-store-engine` — `SegmentBuilderRecallTest` (regression gate
  on default params), `IndexBuildParamSweepTest` (parameter sweep
  harness).
- `vector-store-app` — `WikipediaFilteredRecallIT` (real-embedding
  filtered-recall over the full HTTP path).

## Dependencies

- `com.fasterxml.jackson.core:jackson-databind` (`provided`) — JSONL
  parsing. The consumer brings its own version.

That is the entire dep graph; nothing test-scoped, nothing pulled
transitively into consumers' runtime classpaths.

## Not in this module

- No code under test. This is fixture data only — the modules that
  consume it own the assertions.
- No production runtime use. Always declared as `<scope>test</scope>`
  by consumers.
