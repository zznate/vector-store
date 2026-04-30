package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import java.util.List;
import java.util.Optional;

public interface BucketRepository {

  Bucket create(Bucket bucket);

  Optional<Bucket> findById(String bucketId);

  /**
   * Every bucket the catalog holds, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>admin REST</b> ({@code GET /v1/buckets}).
   * Capped at 5000 rows in SQL — well above realistic deployments;
   * pagination is filed as a follow-up so we can safely lift the cap.
   * Consumer-facing endpoints that need bucket lists must paginate.
   */
  List<Bucket> list();

  void delete(String bucketId);
}
