package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
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
      INSERT INTO vector_bucket (bucket_id, display_name, created_at)
      VALUES (:bucketId, :displayName, :createdAt)
      """)
  void insert(@BindMethods Bucket bucket);

  @SqlQuery(
      """
      SELECT bucket_id, display_name, created_at
        FROM vector_bucket
       WHERE bucket_id = :bucketId
      """)
  Optional<Bucket> findById(@Bind("bucketId") String bucketId);

  @SqlQuery(
      """
      SELECT bucket_id, display_name, created_at
        FROM vector_bucket
       ORDER BY created_at
      """)
  List<Bucket> list();

  @SqlUpdate("DELETE FROM vector_bucket WHERE bucket_id = :bucketId")
  void delete(@Bind("bucketId") String bucketId);
}
