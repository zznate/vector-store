# vector-store-metadata

## Purpose

Per-segment attribute storage and the filter compiler. The attribute sidecar
is JSON Lines in Phase 1; the compiler translates a filter spec into a `Bits`
mask that the JVector searcher accepts.

Also owns the tombstone bitmap (RoaringBitmap) and its AND-into-mask logic —
deletes never rewrite the graph file.

Currently an empty shell. Populated in **prompt 04 (filters)**.

## Public contract

To be defined in prompt 04. The surface will include:

- An `AttributeSidecar` reader / writer.
- A `FilterCompiler` that takes a filter DSL and a segment context and returns
  a `Bits` accept mask.
- A `TombstoneBitmap` type.

## Dependencies

- `vector-store-core` for the domain model.
- `org.roaringbitmap:RoaringBitmap` — arrives in prompt 04.
- `com.fasterxml.jackson.core:jackson-databind` — arrives in prompt 04.

## Local development

Nothing to run yet. Prompt 04 introduces unit tests for sidecar round-trips
and filter compilation against a seeded dataset.

## Not in this module

- No JVector graph construction. That stays in `vector-store-engine`.
- No object-store I/O. That stays in `vector-store-storage`.
