package io.github.zznate.vectorstore.api.auth.fakes;

import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test double for {@link ApiKeyRepository}. Records every call so tests can
 * assert on filter side-effects without a live database.
 */
public final class InMemoryApiKeyRepository implements ApiKeyRepository {

  private final Map<String, ApiKey> keys = new LinkedHashMap<>();
  private final List<TouchCall> touchCalls = new ArrayList<>();

  @Override
  public ApiKey create(ApiKey apiKey) {
    keys.put(apiKey.keyId(), apiKey);
    return apiKey;
  }

  @Override
  public Optional<ApiKey> findById(String keyId) {
    return Optional.ofNullable(keys.get(keyId));
  }

  @Override
  public List<ApiKey> list() {
    return List.copyOf(keys.values());
  }

  @Override
  public void touchLastUsed(String keyId, Instant at) {
    touchCalls.add(new TouchCall(keyId, at));
    ApiKey current = keys.get(keyId);
    if (current != null) {
      keys.put(
          keyId,
          new ApiKey(
              current.keyId(),
              current.secretHash(),
              current.bucketId(),
              current.createdAt(),
              at));
    }
  }

  @Override
  public void delete(String keyId) {
    keys.remove(keyId);
  }

  @Override
  public boolean adminKeyExists() {
    return keys.values().stream().anyMatch(ApiKey::isAdmin);
  }

  public List<TouchCall> touchCalls() {
    return List.copyOf(touchCalls);
  }

  public record TouchCall(String keyId, Instant at) {}
}
