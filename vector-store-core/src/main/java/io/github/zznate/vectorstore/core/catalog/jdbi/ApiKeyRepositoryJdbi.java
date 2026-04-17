package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public class ApiKeyRepositoryJdbi implements ApiKeyRepository {

  private final Jdbi jdbi;

  @Inject
  public ApiKeyRepositoryJdbi(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  public ApiKey create(ApiKey apiKey) {
    jdbi.useExtension(ApiKeyDao.class, dao -> dao.insert(apiKey));
    return apiKey;
  }

  @Override
  public Optional<ApiKey> findById(String keyId) {
    return jdbi.withExtension(ApiKeyDao.class, dao -> dao.findById(keyId));
  }

  @Override
  public List<ApiKey> list() {
    return jdbi.withExtension(ApiKeyDao.class, ApiKeyDao::list);
  }

  @Override
  public void touchLastUsed(String keyId, Instant at) {
    jdbi.useExtension(ApiKeyDao.class, dao -> dao.touchLastUsed(keyId, at));
  }

  @Override
  public void delete(String keyId) {
    jdbi.useExtension(ApiKeyDao.class, dao -> dao.delete(keyId));
  }

  @Override
  public boolean adminKeyExists() {
    return jdbi.withExtension(ApiKeyDao.class, ApiKeyDao::adminKeyExists);
  }
}
