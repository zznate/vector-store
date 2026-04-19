package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(Segment.class)
interface SegmentDao {

  @SqlUpdate(
      """
      INSERT INTO segment (
        segment_id, index_id, state, vector_count, bytes, object_prefix, created_at
      ) VALUES (
        :segmentId, :indexId, :state, :vectorCount, :bytes, :objectPrefix, :createdAt
      )
      """)
  void insert(@BindMethods Segment segment);

  @SqlQuery(
      """
      SELECT segment_id, index_id, state, vector_count, bytes, object_prefix, created_at
        FROM segment
       WHERE segment_id = :segmentId
      """)
  Optional<Segment> findById(@Bind("segmentId") String segmentId);

  @SqlQuery(
      """
      SELECT segment_id, index_id, state, vector_count, bytes, object_prefix, created_at
        FROM segment
       WHERE index_id = :indexId
       ORDER BY created_at
      """)
  List<Segment> listByIndex(@Bind("indexId") String indexId);

  @SqlUpdate("UPDATE segment SET state = :state WHERE segment_id = :segmentId")
  void updateState(@Bind("segmentId") String segmentId, @Bind("state") SegmentState state);

  @SqlUpdate(
      "UPDATE segment SET state = :state, bytes = :bytes WHERE segment_id = :segmentId")
  void updateStateAndBytes(
      @Bind("segmentId") String segmentId,
      @Bind("state") SegmentState state,
      @Bind("bytes") long bytes);

  @SqlUpdate("DELETE FROM segment WHERE segment_id = :segmentId")
  void delete(@Bind("segmentId") String segmentId);
}
