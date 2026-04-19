# vector-store-metadata

Per-segment attribute sidecar, tombstone bitmap, and filter compiler. See
the [repo root README](../README.md) and
[`docs/design-notes.md`](../docs/design-notes.md) for the filter grammar
and the "deletes are tombstone bits" invariant.

## Status

**Empty shell** in Phase 1's bootstrap prompt. Contains only a
`package-info.java`. Real content lands in **prompt 04 (filters)**.

## Role (when populated)

Owns the non-vector state attached to each segment: the attribute sidecar
(JSON Lines in Phase 1), the tombstone bitmap (RoaringBitmap), and the
compiler that turns a filter spec into a `Bits` accept mask the JVector
searcher can consume. Per the Phase 1 invariants in `docs/design-notes.md`,
deletes are tombstone bits — the graph file is never rewritten.

## Public surface (planned)

Defined in prompt 04. Expected entry points: an `AttributeSidecar` reader /
writer, a `FilterCompiler` that emits a `Bits` mask, and a `TombstoneBitmap`
type. The filter predicate format evolves independently of the graph in
Phase 2.

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) for the domain
  model.
- `org.roaringbitmap:RoaringBitmap` — arrives in prompt 04.
- `com.fasterxml.jackson.core:jackson-databind` — arrives in prompt 04.

## Local development

Nothing to run yet. Prompt 04 adds unit tests for sidecar round-trips and
filter compilation against a seeded dataset.

## Not in this module

- No JVector graph construction — see
  [`vector-store-engine`](../vector-store-engine/README.md).
- No object-store I/O — see
  [`vector-store-storage`](../vector-store-storage/README.md).
- No HTTP / REST — see
  [`vector-store-api`](../vector-store-api/README.md).
