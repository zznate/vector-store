package io.github.zznate.vectorstore.api.auth;

import io.github.zznate.vectorstore.api.error.ForbiddenException;
import io.github.zznate.vectorstore.api.error.UnauthorizedException;
import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.List;

/**
 * Authenticates every incoming request by validating the {@code X-Api-Key}
 * header against the catalog's stored Argon2id hashes, then authorises the
 * request based on either an {@link AdminOnly} annotation on the resource or
 * the {@code {bucket}} path parameter when one is present.
 *
 * <p>Token format: {@code keyId.secret}. The keyId lives in the
 * {@code api_key} table as a public identifier; the secret is what we hash.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class ApiKeyAuthenticationFilter implements ContainerRequestFilter {

  public static final String HEADER = "X-Api-Key";
  static final String BUCKET_PATH_PARAM = "bucket";
  static final String PROTECTED_PATH_PREFIX = "v1/";

  private final ApiKeyRepository apiKeys;
  private final PasswordHasher hasher;
  private final Clock clock;

  @Context ResourceInfo resourceInfo;

  @Inject
  public ApiKeyAuthenticationFilter(
      ApiKeyRepository apiKeys, PasswordHasher hasher, Clock clock) {
    this.apiKeys = apiKeys;
    this.hasher = hasher;
    this.clock = clock;
  }

  @Override
  public void filter(ContainerRequestContext ctx) {
    if (!protectedPath(ctx)) {
      // Health, metrics, OpenAPI and other management endpoints are public.
      return;
    }
    ApiKey key = authenticate(ctx);
    authorize(ctx, key);
    apiKeys.touchLastUsed(key.keyId(), clock.instant());
    ctx.setSecurityContext(
        new VectorStoreSecurityContext(
            new BucketScopedPrincipal(key.keyId(), key.bucketId()),
            ctx.getSecurityContext() != null && ctx.getSecurityContext().isSecure()));
  }

  private static boolean protectedPath(ContainerRequestContext ctx) {
    String path = ctx.getUriInfo().getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    return path.startsWith(PROTECTED_PATH_PREFIX) || "v1".equals(path);
  }

  private ApiKey authenticate(ContainerRequestContext ctx) {
    String header = ctx.getHeaderString(HEADER);
    if (header == null || header.isBlank()) {
      throw new UnauthorizedException("missing " + HEADER + " header");
    }
    int dot = header.indexOf('.');
    if (dot <= 0 || dot == header.length() - 1) {
      throw new UnauthorizedException("invalid api key format");
    }
    String keyId = header.substring(0, dot);
    String secret = header.substring(dot + 1);
    ApiKey stored =
        apiKeys.findById(keyId).orElseThrow(() -> new UnauthorizedException("invalid api key"));
    if (!hasher.verify(secret, stored.secretHash())) {
      throw new UnauthorizedException("invalid api key");
    }
    return stored;
  }

  private void authorize(ContainerRequestContext ctx, ApiKey key) {
    if (requiresAdmin()) {
      if (!key.isAdmin()) {
        throw new ForbiddenException("admin api key required");
      }
      return;
    }
    List<String> pathBuckets = ctx.getUriInfo().getPathParameters().get(BUCKET_PATH_PARAM);
    if (pathBuckets == null || pathBuckets.isEmpty()) {
      return;
    }
    String pathBucket = pathBuckets.get(0);
    if (!key.isAdmin() && !pathBucket.equals(key.bucketId())) {
      throw new ForbiddenException("api key scope does not match target bucket");
    }
  }

  private boolean requiresAdmin() {
    if (resourceInfo == null) {
      return false;
    }
    Method method = resourceInfo.getResourceMethod();
    Class<?> clazz = resourceInfo.getResourceClass();
    return (method != null && method.isAnnotationPresent(AdminOnly.class))
        || (clazz != null && clazz.isAnnotationPresent(AdminOnly.class));
  }
}
