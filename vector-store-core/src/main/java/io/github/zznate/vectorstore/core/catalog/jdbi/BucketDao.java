package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(Bucket.class)
interface BucketDao {

  @SqlUpdate(
      """
      INSERT INTO vector_bucket (bucket_id, display_name, created_at, deleted_at)
      VALUES (:bucketId, :displayName, :createdAt, :deletedAt)
      """)
  void insert(@BindMethods Bucket bucket);

  @SqlQuery(
      """
      SELECT bucket_id, display_name, created_at, deleted_at
        FROM vector_bucket
       WHERE bucket_id = :bucketId
         AND deleted_at IS NULL
      """)
  Optional<Bucket> findById(@Bind("bucketId") String bucketId);

  @SqlQuery(
      """
      SELECT bucket_id, display_name, created_at, deleted_at
        FROM vector_bucket
       WHERE bucket_id = :bucketId
      """)
  Optional<Bucket> findIncludingDeleted(@Bind("bucketId") String bucketId);

  @SqlQuery(
      """
      SELECT bucket_id, display_name, created_at, deleted_at
        FROM vector_bucket
       WHERE deleted_at IS NULL
       ORDER BY created_at
       LIMIT 5000
      """)
  List<Bucket> list();

  @SqlQuery(
      """
      SELECT bucket_id, display_name, created_at, deleted_at
        FROM vector_bucket
       WHERE deleted_at IS NOT NULL
         AND deleted_at < :cutoff
       ORDER BY deleted_at
       LIMIT 5000
      """)
  List<Bucket> listSoftDeletedBefore(@Bind("cutoff") Instant cutoff);

  @SqlUpdate(
      """
      UPDATE vector_bucket
         SET deleted_at = :at
       WHERE bucket_id = :bucketId
         AND deleted_at IS NULL
      """)
  int softDelete(@Bind("bucketId") String bucketId, @Bind("at") Instant at);

  @SqlUpdate(
      """
      UPDATE vector_bucket
         SET deleted_at = NULL
       WHERE bucket_id = :bucketId
         AND deleted_at IS NOT NULL
      """)
  int restore(@Bind("bucketId") String bucketId);

  @SqlUpdate("DELETE FROM vector_bucket WHERE bucket_id = :bucketId")
  void hardDelete(@Bind("bucketId") String bucketId);
}
