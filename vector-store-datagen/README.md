# vector-store-datagen

Tooling that produces and manages data the
[vector-store](../README.md) project consumes. See the repo root for the
project overview; this module is deliberately outside the service module
graph and has no sibling dependencies.

## Role

Nothing here runs in CI. Nothing here is on the runtime classpath of the
service. Every action is explicit: you invoke a subcommand, it writes an
artifact to disk, you commit the artifact if you want to share it.

Phase 2 uses this to generate the recall-test fixture that
[`vector-store-engine`](../vector-store-engine/README.md)'s
`SegmentBuilderRecallTest` reads at test time. Later phases grow the
subcommand set (demo seeding, benchmark corpora, etc.).

## Commands

| Subcommand | Purpose |
|---|---|
| `generate-recall-fixture` | Fetch the pinned Wikipedia ML corpus, chunk, embed, and write `corpus.jsonl` + `queries.jsonl` + `README.md` into the given output directory. |
| `validate-fixture` | Sanity-check a previously-written fixture: every entry parses, embedding dimensions agree, every query's `expectedArticleSlug` exists in the corpus. |
| `help` | Print the subcommand list. |

## Running

```
./mvnw -pl vector-store-datagen exec:java@generate-recall-fixture
./mvnw -pl vector-store-datagen exec:java@validate-fixture
```

The first run of `generate-recall-fixture` downloads ~400 MB of PyTorch
native libraries plus the
[`sentence-transformers/all-MiniLM-L6-v2`](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
weights into `~/.djl.ai/`. Subsequent runs are fast. Model artifacts are
`.gitignore`d.

## Dependencies

- [DJL](https://djl.ai/) + HuggingFace tokenizers + PyTorch engine for
  embeddings.
- [JSoup](https://jsoup.org/) for stripping Wikipedia HTML to plain text.
- [Jackson](https://github.com/FasterXML/jackson) for JSON Lines I/O.
- JDK 21 `HttpClient` for Wikipedia fetches.

No internal sibling dependencies. Not on the runtime classpath of
`vector-store-app`.

## Not in this module

- No JVector, no catalog, no REST surface.
- No CI-executed tests.
- No service-side runtime code.
