# vector-store-engine

## Purpose

Adapter layer over JVector: builder orchestration, on-disk graph writer, and
the segment searcher. Owns the dense-ordinal ↔ user-id translation for a given
segment.

Currently an empty shell. Populated in **prompt 02 (ingest-local)**.

## Public contract

To be defined in prompt 02. The surface will expose a segment-builder, a
segment-searcher, and the per-segment ordinal map.

## Dependencies

- `vector-store-core` for the domain model and catalog types.
- `io.github.jbellis:jvector` — arrives in prompt 02.

## Local development

Nothing to run yet. After prompt 02 this module will have its own unit tests
using a small seeded vector set.

## Not in this module

- No object-store I/O. That stays in `vector-store-storage`.
- No attribute filtering. That stays in `vector-store-metadata`.
