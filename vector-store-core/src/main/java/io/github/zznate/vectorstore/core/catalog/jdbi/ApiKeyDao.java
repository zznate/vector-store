package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(ApiKey.class)
interface ApiKeyDao {

  @SqlUpdate(
      """
      INSERT INTO api_key (key_id, secret_hash, bucket_id, created_at, last_used_at)
      VALUES (:keyId, :secretHash, :bucketId, :createdAt, :lastUsedAt)
      """)
  void insert(@BindMethods ApiKey apiKey);

  @SqlQuery(
      """
      SELECT key_id, secret_hash, bucket_id, created_at, last_used_at
        FROM api_key
       WHERE key_id = :keyId
      """)
  Optional<ApiKey> findById(@Bind("keyId") String keyId);

  @SqlQuery(
      """
      SELECT key_id, secret_hash, bucket_id, created_at, last_used_at
        FROM api_key
       ORDER BY created_at
       LIMIT 5000
      """)
  List<ApiKey> list();

  @SqlUpdate("UPDATE api_key SET last_used_at = :at WHERE key_id = :keyId")
  void touchLastUsed(@Bind("keyId") String keyId, @Bind("at") Instant at);

  @SqlUpdate("DELETE FROM api_key WHERE key_id = :keyId")
  void delete(@Bind("keyId") String keyId);

  @SqlQuery("SELECT EXISTS(SELECT 1 FROM api_key WHERE bucket_id IS NULL)")
  boolean adminKeyExists();
}
