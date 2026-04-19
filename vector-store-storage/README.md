# vector-store-storage

Object-store integration for vector-store: S3 client wiring, the
JVector-compatible `RandomAccessReader` that serves ranged GETs, and the
on-heap block cache that makes those reads affordable. See the
[repo root README](../README.md) and
[`docs/design-notes.md`](../docs/design-notes.md) for the object-store
layout and Phase 1 latency target.

## Status

**Empty shell** in Phase 1's bootstrap prompt. Contains only a
`package-info.java`. Real content lands in **prompt 03 (minio)**.

## Role (when populated)

Owns everything about talking to the object store: building the AWS SDK v2
`S3Client`, producing a `RandomAccessReader` that JVector can feed to
`OnDiskGraphIndex`, and caching blocks in-process so query fan-out does not
re-download segment bytes on every call. The object store itself is an
implementation detail; this module is the seam between it and the rest of
the service.

## Public surface (planned)

Defined in prompt 03. Expected entry point: a `SegmentReaderFactory` that,
given a `Segment` from
[`vector-store-core`](../vector-store-core/README.md), returns a JVector
`RandomAccessReader`. Block cache is internal.

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) for the catalog
  records.
- `software.amazon.awssdk:s3` — arrives in prompt 03.

## Local development

Nothing to run yet. Prompt 03 introduces Testcontainers-backed MinIO for
integration tests (`*IT.java`, driven by Failsafe in `./mvnw verify`).

## Not in this module

- No JVector graph construction — see
  [`vector-store-engine`](../vector-store-engine/README.md).
- No catalog SQL — see
  [`vector-store-core`](../vector-store-core/README.md).
- No HTTP / REST — see
  [`vector-store-api`](../vector-store-api/README.md).
