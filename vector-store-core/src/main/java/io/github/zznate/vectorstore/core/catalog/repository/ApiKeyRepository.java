package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {

  ApiKey create(ApiKey apiKey);

  Optional<ApiKey> findById(String keyId);

  /**
   * Every API key the catalog holds, ordered by {@code created_at}.
   *
   * <p>Caller invariant: <b>tests only today</b>. No production
   * caller iterates the key set; the auth path uses
   * {@link #findById(String)} keyed by the {@code X-Api-Key} header.
   * Capped at 5000 rows in SQL as a safety net should an
   * administration endpoint ever consume this; that endpoint should
   * paginate before lifting the cap.
   */
  List<ApiKey> list();

  void touchLastUsed(String keyId, Instant at);

  void delete(String keyId);

  boolean adminKeyExists();
}
