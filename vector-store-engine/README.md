# vector-store-engine

JVector adapter for vector-store: graph builder, on-disk segment writer, and
per-segment searcher. See the [repo root README](../README.md) for the
project overview and
[`docs/design-notes.md`](../docs/design-notes.md) for the engine parameters
and segment layout.

## Status

**Empty shell** in Phase 1's bootstrap prompt. Contains only a
`package-info.java`. Real content lands in **prompt 02 (ingest-local)**.

## Role (when populated)

Owns the translation between the user-facing world (string IDs, attribute
maps) and JVector's dense-ordinal graph: the segment-builder that consumes
the write buffer, the on-disk writer for `graph.jvec` and `ordinals.jsonl`,
and the segment-searcher the query fan-out invokes.

Per the Phase 1 invariants in `docs/design-notes.md`, the user-id ↔
(segment_id, ordinal) mapping lives here from day one — even when each
index has exactly one segment.

## Public surface (planned)

Defined in prompt 02. Expected entry points: a `SegmentBuilder`, a
`SegmentSearcher`, and an `OrdinalMap`. JVector arrives as
`io.github.jbellis:jvector` (version managed in the parent POM).

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) for the domain model
  and catalog types.
- `io.github.jbellis:jvector` — arrives in prompt 02.

## Local development

Nothing to run yet. Prompt 02 adds unit tests over a small seeded vector
set.

## Not in this module

- No object-store I/O — see
  [`vector-store-storage`](../vector-store-storage/README.md).
- No attribute filtering or tombstones — see
  [`vector-store-metadata`](../vector-store-metadata/README.md).
- No HTTP / REST — see
  [`vector-store-api`](../vector-store-api/README.md).
