# vector-store-storage

Object-store integration for vector-store: S3 client wiring, the
JVector-compatible `RandomAccessReader` that serves ranged GETs, and the
on-heap block cache that makes those reads affordable. See the
[repo root README](../README.md) and
[`docs/design-notes.md`](../docs/design-notes.md) for the object-store
layout and Phase 1 latency target.

## Status

**Empty shell** through phase 2. Contains only a `package-info.java`.
Real content lands in **phase 3 (minio)**.

## Role (when populated)

Owns everything about talking to the object store: building the AWS SDK v2
`S3Client`, producing a `RandomAccessReader` that JVector can feed to
`OnDiskGraphIndex`, and caching blocks in-process so query fan-out does not
re-download segment bytes on every call. The object store itself is an
implementation detail; this module is the seam between it and the rest of
the service.

## Public surface (planned)

Defined in phase 3. Will provide an S3-backed implementation of
[`core.segment.SegmentStore`](../vector-store-core/src/main/java/io/github/zznate/vectorstore/core/segment/SegmentStore.java)
(the same interface `vector-store-engine`'s `LocalSegmentStore` already
implements), plus an on-heap block cache for the ranged-GET path.

## Dependencies

- [`vector-store-core`](../vector-store-core/README.md) for the catalog
  records.
- `software.amazon.awssdk:s3` — arrives in phase 3.

## Local development

Nothing to run yet. Phase 3 introduces Testcontainers-backed MinIO for
integration tests (`*IT.java`, driven by Failsafe in `./mvnw verify`).

## Not in this module

- No JVector graph construction — see
  [`vector-store-engine`](../vector-store-engine/README.md).
- No catalog SQL — see
  [`vector-store-core`](../vector-store-core/README.md).
- No HTTP / REST — see
  [`vector-store-api`](../vector-store-api/README.md).
