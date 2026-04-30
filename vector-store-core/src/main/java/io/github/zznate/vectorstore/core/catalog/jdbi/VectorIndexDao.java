package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(VectorIndex.class)
interface VectorIndexDao {

  @SqlUpdate(
      """
      INSERT INTO vector_index (
        index_id, bucket_id, display_name, dimension, metric,
        engine_params, created_at, deleted_at
      ) VALUES (
        :indexId, :bucketId, :displayName, :dimension, :metric,
        :engineParams, :createdAt, :deletedAt
      )
      """)
  void insert(@BindMethods VectorIndex index);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric,
             engine_params, created_at, deleted_at
        FROM vector_index
       WHERE index_id = :indexId
         AND deleted_at IS NULL
      """)
  Optional<VectorIndex> findById(@Bind("indexId") String indexId);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric,
             engine_params, created_at, deleted_at
        FROM vector_index
       WHERE index_id = :indexId
      """)
  Optional<VectorIndex> findIncludingDeleted(@Bind("indexId") String indexId);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric,
             engine_params, created_at, deleted_at
        FROM vector_index
       WHERE bucket_id = :bucketId
         AND deleted_at IS NULL
       ORDER BY created_at
       LIMIT 5000
      """)
  List<VectorIndex> listByBucket(@Bind("bucketId") String bucketId);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric,
             engine_params, created_at, deleted_at
        FROM vector_index
       WHERE deleted_at IS NULL
       ORDER BY created_at
       LIMIT 10000
      """)
  List<VectorIndex> listAll();

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric,
             engine_params, created_at, deleted_at
        FROM vector_index
       WHERE deleted_at IS NOT NULL
         AND deleted_at < :cutoff
       ORDER BY deleted_at
       LIMIT 5000
      """)
  List<VectorIndex> listSoftDeletedBefore(@Bind("cutoff") Instant cutoff);

  @SqlQuery(
      "SELECT COUNT(*) FROM vector_index WHERE bucket_id = :bucketId")
  int countAnyByBucket(@Bind("bucketId") String bucketId);

  @SqlUpdate(
      """
      UPDATE vector_index
         SET deleted_at = :at
       WHERE index_id = :indexId
         AND deleted_at IS NULL
      """)
  int softDelete(@Bind("indexId") String indexId, @Bind("at") Instant at);

  @SqlUpdate(
      """
      UPDATE vector_index
         SET deleted_at = NULL
       WHERE index_id = :indexId
         AND deleted_at IS NOT NULL
      """)
  int restore(@Bind("indexId") String indexId);

  @SqlUpdate("DELETE FROM vector_index WHERE index_id = :indexId")
  void hardDelete(@Bind("indexId") String indexId);
}
