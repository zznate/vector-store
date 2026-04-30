package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import java.util.List;
import java.util.Optional;

public interface VectorIndexRepository {

  VectorIndex create(VectorIndex index);

  Optional<VectorIndex> findById(String indexId);

  /**
   * Every index in {@code bucketId}, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>admin / REST</b>
   * ({@code GET /v1/buckets/{bucket}/indexes} and the
   * {@link io.github.zznate.vectorstore.core.catalog.repository.BucketRepository}-empty
   * check on bucket delete). Capped at 5000 rows in SQL — well above
   * realistic per-bucket index counts; pagination is filed as a
   * follow-up so we can lift the cap safely.
   */
  List<VectorIndex> listByBucket(String bucketId);

  /**
   * Every index across every bucket, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>startup-only</b>. Today the only caller is
   * {@link io.github.zznate.vectorstore.core.catalog.model.IndexParamsValidator}
   * via the boot-time hook in {@code vector-store-app}. <b>Do not call
   * from a request path</b> — every call scans the table. Capped at
   * 10000 rows in SQL as a safety net; if a deployment ever has more
   * than that many indexes the validator must be paged or sampled.
   */
  List<VectorIndex> listAll();

  void delete(String indexId);
}
