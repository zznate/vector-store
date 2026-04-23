-- Phase 2 — catalog-backed staged tombstones.
--
-- Before Phase 2, uncommitted :delete requests lived only in
-- InMemoryTombstones and were lost on restart. The staged_tombstone table
-- persists the staging set so delete requests survive JVM crashes. On the
-- next commit, CommitCoordinator drains the set into each active segment's
-- tombstones.roar sidecar and clears the rows inside the same transaction as
-- the manifest_version append, so staging and manifest are never out of
-- agreement.
--
-- (index_id, user_id) primary key makes repeated :delete of the same id
-- idempotent. ON DELETE CASCADE lets index deletion clean up its own
-- staging set without a separate sweep.

CREATE TABLE staged_tombstone (
  index_id   TEXT      NOT NULL REFERENCES vector_index(index_id) ON DELETE CASCADE,
  user_id    TEXT      NOT NULL,
  staged_at  TIMESTAMP NOT NULL,
  PRIMARY KEY (index_id, user_id)
);

CREATE INDEX idx_staged_tombstone_index ON staged_tombstone(index_id);
