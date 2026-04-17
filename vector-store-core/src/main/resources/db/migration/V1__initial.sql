-- vector-store catalog — initial schema.
--
-- Authoritative source: docs/design-notes.md > "Catalog schema (Phase 1)".
-- Kept SQLite-compatible while staying close to Postgres-portable SQL; any
-- dialect-specific construct must be justified in a code review comment.
-- Column case is snake_case; row-mapper configuration converts to
-- camelCase record components.

CREATE TABLE api_key (
  key_id        TEXT PRIMARY KEY,
  secret_hash   TEXT NOT NULL,
  bucket_id     TEXT,                   -- NULL = admin key; soft reference
  created_at    TIMESTAMP NOT NULL,
  last_used_at  TIMESTAMP
);

CREATE TABLE vector_bucket (
  bucket_id     TEXT PRIMARY KEY,
  display_name  TEXT NOT NULL,
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE vector_index (
  index_id      TEXT PRIMARY KEY,
  bucket_id     TEXT NOT NULL REFERENCES vector_bucket(bucket_id),
  display_name  TEXT NOT NULL,
  dimension     INTEGER NOT NULL,
  metric        TEXT NOT NULL,          -- COSINE | EUCLIDEAN | DOT_PRODUCT
  engine_params TEXT NOT NULL,          -- JSON: {m, beamWidth, pqSubspaces, ...}
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE segment (
  segment_id    TEXT PRIMARY KEY,
  index_id      TEXT NOT NULL REFERENCES vector_index(index_id),
  state         TEXT NOT NULL,          -- BUILDING | ACTIVE | RETIRED
  vector_count  INTEGER NOT NULL,
  bytes         BIGINT NOT NULL,
  object_prefix TEXT NOT NULL,
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE manifest_version (
  index_id      TEXT NOT NULL REFERENCES vector_index(index_id),
  version       INTEGER NOT NULL,
  segment_ids   TEXT NOT NULL,          -- JSON array of active segments
  created_at    TIMESTAMP NOT NULL,
  PRIMARY KEY (index_id, version)
);

CREATE INDEX idx_segment_index ON segment(index_id);
CREATE INDEX idx_vector_index_bucket ON vector_index(bucket_id);
CREATE INDEX idx_api_key_bucket ON api_key(bucket_id);
