-- Phase 2 — soft delete for bucket and index.
--
-- Adds a nullable `deleted_at` to `vector_bucket` and `vector_index`. A NULL
-- value means the row is active; a non-NULL timestamp means the row was
-- soft-deleted at that instant and is hidden from the REST surface.
--
-- Active reads everywhere filter `WHERE deleted_at IS NULL`. The
-- RetentionSweep eventually hard-deletes rows whose `deleted_at + window`
-- has elapsed, at which point segment / manifest_version / object-store
-- cleanup cascades from the index hard-delete.
--
-- Bucket hard-delete is gated on no remaining child indexes (in any state)
-- so that an index restore inside the retention window cannot orphan a
-- restored child onto a dead bucket.

ALTER TABLE vector_bucket ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE vector_index  ADD COLUMN deleted_at TIMESTAMP NULL;
