package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(ManifestVersion.class)
interface ManifestVersionDao {

  @SqlUpdate(
      """
      INSERT INTO manifest_version (index_id, version, segment_ids, created_at)
      VALUES (:indexId, :version, :segmentIds, :createdAt)
      """)
  void insert(@BindMethods ManifestVersion version);

  @SqlQuery(
      """
      SELECT index_id, version, segment_ids, created_at
        FROM manifest_version
       WHERE index_id = :indexId
       ORDER BY version DESC
       LIMIT 1
      """)
  Optional<ManifestVersion> findCurrent(@Bind("indexId") String indexId);

  @SqlQuery(
      """
      SELECT index_id, version, segment_ids, created_at
        FROM manifest_version
       WHERE index_id = :indexId
       ORDER BY version
      """)
  List<ManifestVersion> listByIndex(@Bind("indexId") String indexId);
}
