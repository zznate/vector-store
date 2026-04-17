package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

public record ApiKey(
    String keyId, String secretHash, String bucketId, Instant createdAt, Instant lastUsedAt) {

  public ApiKey {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(secretHash, "secretHash");
    Objects.requireNonNull(createdAt, "createdAt");
    // bucketId may be null — null scope means an admin key.
    // lastUsedAt may be null — key has never been used.
  }

  public boolean isAdmin() {
    return bucketId == null;
  }
}
