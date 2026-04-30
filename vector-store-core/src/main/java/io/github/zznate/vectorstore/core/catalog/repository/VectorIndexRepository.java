package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VectorIndexRepository {

  VectorIndex create(VectorIndex index);

  /**
   * Active index lookup. Returns {@link Optional#empty()} when the index
   * does not exist <em>or</em> is in the soft-delete retention window.
   * Used by REST read paths so soft-deleted rows are invisible.
   */
  Optional<VectorIndex> findById(String indexId);

  /**
   * Index lookup that includes soft-deleted rows. Used by the re-creation
   * guard, restore, and the retention sweep.
   */
  Optional<VectorIndex> findIncludingDeleted(String indexId);

  /**
   * Active indexes in {@code bucketId}, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>admin / REST</b>
   * ({@code GET /v1/buckets/{bucket}/indexes} and the bucket-empty check
   * on bucket soft-delete). Capped at 5000 rows; pagination is filed as
   * a follow-up.
   */
  List<VectorIndex> listByBucket(String bucketId);

  /**
   * Active indexes across every bucket, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>startup-only</b>. Today the only caller is
   * {@link io.github.zznate.vectorstore.core.catalog.model.IndexParamsValidator}
   * via the boot-time hook in {@code vector-store-app}. Capped at 10000
   * rows in SQL as a safety net.
   */
  List<VectorIndex> listAll();

  /**
   * Soft-deleted indexes whose {@code deleted_at} is strictly before
   * {@code cutoff}, ordered by {@code deleted_at}.
   *
   * <p>Caller invariant: <b>retention sweep only</b>. Capped at 5000
   * rows per call; the sweep loops until empty.
   */
  List<VectorIndex> listSoftDeletedBefore(Instant cutoff);

  /**
   * Number of indexes (in any state, including soft-deleted) that point
   * at {@code bucketId}. Used by the retention sweep to decide whether a
   * soft-deleted bucket has any remaining children blocking hard-delete.
   */
  int countAnyByBucket(String bucketId);

  /**
   * Mark the index soft-deleted. Returns {@code true} if a fresh
   * soft-delete row was written, {@code false} if the index did not
   * exist or was already soft-deleted.
   */
  boolean softDelete(String indexId, Instant at);

  /**
   * Clear the soft-delete marker. Returns {@code true} if a row was
   * restored, {@code false} if the index does not exist or is already
   * active.
   */
  boolean restore(String indexId);

  /**
   * Permanently remove the row. Caller invariant: <b>retention sweep
   * only</b>; production REST paths use {@link #softDelete(String,
   * Instant)} instead. The sweep is responsible for cascading object-store
   * + sidecar cleanup before this call.
   */
  void hardDelete(String indexId);
}
