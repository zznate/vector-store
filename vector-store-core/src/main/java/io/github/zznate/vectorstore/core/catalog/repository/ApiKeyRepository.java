package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {

  ApiKey create(ApiKey apiKey);

  Optional<ApiKey> findById(String keyId);

  List<ApiKey> list();

  void touchLastUsed(String keyId, Instant at);

  void delete(String keyId);

  boolean adminKeyExists();
}
