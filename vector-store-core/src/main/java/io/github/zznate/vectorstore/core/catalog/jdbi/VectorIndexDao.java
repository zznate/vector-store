package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
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
        index_id, bucket_id, display_name, dimension, metric, engine_params, created_at
      ) VALUES (
        :indexId, :bucketId, :displayName, :dimension, :metric, :engineParams, :createdAt
      )
      """)
  void insert(@BindMethods VectorIndex index);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric, engine_params, created_at
        FROM vector_index
       WHERE index_id = :indexId
      """)
  Optional<VectorIndex> findById(@Bind("indexId") String indexId);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric, engine_params, created_at
        FROM vector_index
       WHERE bucket_id = :bucketId
       ORDER BY created_at
      """)
  List<VectorIndex> listByBucket(@Bind("bucketId") String bucketId);

  @SqlQuery(
      """
      SELECT index_id, bucket_id, display_name, dimension, metric, engine_params, created_at
        FROM vector_index
       ORDER BY created_at
      """)
  List<VectorIndex> listAll();

  @SqlUpdate("DELETE FROM vector_index WHERE index_id = :indexId")
  void delete(@Bind("indexId") String indexId);
}
