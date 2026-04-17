# vector-store-storage

## Purpose

Object-store integration for vector-store: S3 client wiring, the
`RandomAccessReader` that serves ranged reads from S3, and the on-heap block
cache that makes those reads affordable.

Currently an empty shell. Populated in **prompt 03 (minio)**.

## Public contract

To be defined in prompt 03. The surface will expose a `SegmentReaderFactory`
that returns a JVector-compatible `RandomAccessReader` for any segment in the
catalog.

## Dependencies

- `vector-store-core` for the domain model.
- `software.amazon.awssdk:s3` — arrives in prompt 03.

## Local development

Nothing to run yet. Prompt 03 introduces Testcontainers-backed MinIO for
integration tests.

## Not in this module

- No JVector-specific logic. That stays in `vector-store-engine`.
- No REST wiring. That stays in `vector-store-api`.
