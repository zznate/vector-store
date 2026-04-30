package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BucketRepository {

  Bucket create(Bucket bucket);

  /**
   * Active bucket lookup. Returns {@link Optional#empty()} when the bucket
   * does not exist <em>or</em> is in the soft-delete retention window. Use
   * this from REST read paths so retention-window rows are invisible to
   * consumers.
   */
  Optional<Bucket> findById(String bucketId);

  /**
   * Bucket lookup that includes soft-deleted rows. Returns {@link
   * Optional#empty()} only when no row exists at all. Used by the
   * re-creation guard (so a re-create against a soft-deleted name returns
   * a clear retention error rather than a primary-key crash), restore, and
   * the retention sweep.
   */
  Optional<Bucket> findIncludingDeleted(String bucketId);

  /**
   * Active buckets, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>admin REST</b> ({@code GET /v1/buckets}).
   * Soft-deleted buckets are filtered out at SQL. Capped at 5000 rows in
   * SQL — well above realistic deployments; pagination is filed as a
   * follow-up so we can safely lift the cap.
   */
  List<Bucket> list();

  /**
   * Soft-deleted buckets whose {@code deleted_at} is strictly before
   * {@code cutoff}, ordered by {@code deleted_at}.
   *
   * <p>Caller invariant: <b>retention sweep only</b>. Capped at 5000 rows
   * per call so a single sweep iteration cannot OOM; the sweep loops
   * until empty.
   */
  List<Bucket> listSoftDeletedBefore(Instant cutoff);

  /**
   * Mark the bucket soft-deleted. Idempotent: if the bucket is already
   * soft-deleted, the existing {@code deleted_at} is preserved (the SQL
   * filters on {@code deleted_at IS NULL}). Returns {@code true} if a
   * fresh soft-delete row was written, {@code false} if the bucket did
   * not exist or was already soft-deleted.
   */
  boolean softDelete(String bucketId, Instant at);

  /**
   * Clear the soft-delete marker. Returns {@code true} if a row was
   * restored, {@code false} if the bucket does not exist or is already
   * active. Hard-deleted buckets cannot be restored — caller must check
   * {@link #findIncludingDeleted(String)} returns non-empty before
   * presenting "still restorable" semantics.
   */
  boolean restore(String bucketId);

  /**
   * Permanently remove the row. Caller invariant: <b>retention sweep
   * only</b>; the sweep guarantees no child indexes (in any state) point
   * at this bucket before calling. Production REST paths use {@link
   * #softDelete(String, Instant)} instead.
   */
  void hardDelete(String bucketId);
}
