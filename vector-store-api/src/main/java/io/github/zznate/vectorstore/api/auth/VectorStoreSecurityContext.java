package io.github.zznate.vectorstore.api.auth;

import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;

/**
 * {@link SecurityContext} wrapping a {@link BucketScopedPrincipal}. Exposes
 * a single role, {@code "admin"}, which is true exactly when the underlying
 * key has admin scope.
 */
public final class VectorStoreSecurityContext implements SecurityContext {

  public static final String ADMIN_ROLE = "admin";
  public static final String AUTH_SCHEME = "ApiKey";

  private final BucketScopedPrincipal principal;
  private final boolean secure;

  public VectorStoreSecurityContext(BucketScopedPrincipal principal, boolean secure) {
    this.principal = principal;
    this.secure = secure;
  }

  @Override
  public Principal getUserPrincipal() {
    return principal;
  }

  @Override
  public boolean isUserInRole(String role) {
    return ADMIN_ROLE.equals(role) && principal.isAdmin();
  }

  @Override
  public boolean isSecure() {
    return secure;
  }

  @Override
  public String getAuthenticationScheme() {
    return AUTH_SCHEME;
  }
}
