package io.github.zznate.vectorstore.core.catalog.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

interface StagedTombstoneDao {

  @SqlBatch(
      """
      INSERT OR IGNORE INTO staged_tombstone (index_id, user_id, staged_at)
      VALUES (:indexId, :userId, :stagedAt)
      """)
  void stage(
      @Bind("indexId") String indexId,
      @Bind("userId") Iterable<String> userIds,
      @Bind("stagedAt") Instant stagedAt);

  @SqlBatch(
      """
      DELETE FROM staged_tombstone
       WHERE index_id = :indexId AND user_id = :userId
      """)
  void unstage(@Bind("indexId") String indexId, @Bind("userId") Iterable<String> userIds);

  @SqlQuery("SELECT user_id FROM staged_tombstone WHERE index_id = :indexId")
  List<String> snapshot(@Bind("indexId") String indexId);

  @SqlQuery(
      """
      SELECT 1 FROM staged_tombstone
       WHERE index_id = :indexId AND user_id = :userId
       LIMIT 1
      """)
  Optional<Integer> existsStaged(
      @Bind("indexId") String indexId, @Bind("userId") String userId);

  @SqlQuery("SELECT COUNT(*) FROM staged_tombstone WHERE index_id = :indexId")
  int count(@Bind("indexId") String indexId);
}
