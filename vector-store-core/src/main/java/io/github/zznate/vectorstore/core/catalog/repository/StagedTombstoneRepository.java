package io.github.zznate.vectorstore.core.catalog.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Persistent staging set for uncommitted delete requests. Rows live in the
 * {@code staged_tombstone} table and survive process restart; {@link
 * io.github.zznate.vectorstore.core.catalog.jdbi.CatalogCommitPublisher}
 * drains them inside the same transaction as the manifest-version append on
 * each successful commit.
 *
 * <p>{@link #stage} is idempotent — repeated staging of the same
 * {@code (index_id, user_id)} pair is a no-op and preserves the original
 * {@code staged_at} timestamp.
 */
public interface StagedTombstoneRepository {

  /** Idempotently stage the given user IDs for {@code indexId}. */
  void stage(String indexId, Collection<String> userIds, Instant stagedAt);

  /**
   * Remove the given user IDs from the staged set for {@code indexId}.
   * Callers requiring atomicity with a manifest-version append should use
   * {@code CatalogCommitPublisher} instead.
   */
  void unstage(String indexId, Collection<String> userIds);

  /** Snapshot of the current staged IDs for {@code indexId}. */
  Set<String> snapshot(String indexId);

  /** True if {@code userId} is currently staged for {@code indexId}. */
  boolean isStaged(String indexId, String userId);

  /** Count of staged rows for {@code indexId}. */
  int count(String indexId);
}
