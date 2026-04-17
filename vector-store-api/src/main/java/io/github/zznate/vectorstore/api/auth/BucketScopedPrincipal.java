package io.github.zznate.vectorstore.api.auth;

import java.security.Principal;

/**
 * Principal attached to the {@code SecurityContext} after successful
 * API-key authentication. A {@code null} {@code bucketId} means the key has
 * admin scope (not tied to any bucket).
 */
public record BucketScopedPrincipal(String keyId, String bucketId) implements Principal {

  @Override
  public String getName() {
    return keyId;
  }

  public boolean isAdmin() {
    return bucketId == null;
  }
}
