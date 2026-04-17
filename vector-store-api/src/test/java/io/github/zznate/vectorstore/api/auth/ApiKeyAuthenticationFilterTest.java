package io.github.zznate.vectorstore.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.zznate.vectorstore.api.auth.fakes.InMemoryApiKeyRepository;
import io.github.zznate.vectorstore.api.error.ForbiddenException;
import io.github.zznate.vectorstore.api.error.UnauthorizedException;
import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApiKeyAuthenticationFilterTest {

  private static final Instant FROZEN_NOW = Instant.parse("2026-04-17T12:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FROZEN_NOW, ZoneOffset.UTC);

  private InMemoryApiKeyRepository apiKeys;
  private PasswordHasher hasher;
  private ApiKeyAuthenticationFilter filter;

  private String adminSecretHash;
  private String demoSecretHash;

  @BeforeEach
  void setUp() {
    apiKeys = new InMemoryApiKeyRepository();
    hasher = new Argon2PasswordHasher(1, 1024, 1);
    filter = new ApiKeyAuthenticationFilter(apiKeys, hasher, FIXED_CLOCK);

    adminSecretHash = hasher.hash("admin-secret");
    demoSecretHash = hasher.hash("demo-secret");

    apiKeys.create(new ApiKey("admin-key", adminSecretHash, null, Instant.EPOCH, null));
    apiKeys.create(new ApiKey("demo-key", demoSecretHash, "demo", Instant.EPOCH, null));
  }

  @Test
  void missingHeaderIsUnauthorized() {
    ContainerRequestContext ctx = contextWith(null, noPathParams(), AdminResource.class);

    assertThatExceptionOfType(UnauthorizedException.class)
        .isThrownBy(() -> filter.filter(ctx))
        .withMessageContaining("missing X-Api-Key");
  }

  @Test
  void blankHeaderIsUnauthorized() {
    ContainerRequestContext ctx = contextWith("   ", noPathParams(), AdminResource.class);

    assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(() -> filter.filter(ctx));
  }

  @Test
  void malformedHeaderWithoutDotIsUnauthorized() {
    ContainerRequestContext ctx = contextWith("nodothere", noPathParams(), AdminResource.class);

    assertThatExceptionOfType(UnauthorizedException.class)
        .isThrownBy(() -> filter.filter(ctx))
        .withMessageContaining("invalid api key format");
  }

  @Test
  void unknownKeyIdIsUnauthorized() {
    ContainerRequestContext ctx =
        contextWith("nosuch.secret", noPathParams(), AdminResource.class);

    assertThatExceptionOfType(UnauthorizedException.class)
        .isThrownBy(() -> filter.filter(ctx))
        .withMessageContaining("invalid api key");
  }

  @Test
  void wrongSecretIsUnauthorized() {
    ContainerRequestContext ctx =
        contextWith("admin-key.wrong-secret", noPathParams(), AdminResource.class);

    assertThatExceptionOfType(UnauthorizedException.class)
        .isThrownBy(() -> filter.filter(ctx))
        .withMessageContaining("invalid api key");
  }

  @Test
  void nonAdminKeyAgainstAdminOnlyEndpointIsForbidden() {
    ContainerRequestContext ctx =
        contextWith("demo-key.demo-secret", noPathParams(), AdminResource.class);

    assertThatExceptionOfType(ForbiddenException.class)
        .isThrownBy(() -> filter.filter(ctx))
        .withMessageContaining("admin api key required");
  }

  @Test
  void bucketScopedKeyAgainstDifferentBucketIsForbidden() {
    ContainerRequestContext ctx =
        contextWith("demo-key.demo-secret", bucket("other"), BucketScopedResource.class);

    assertThatExceptionOfType(ForbiddenException.class)
        .isThrownBy(() -> filter.filter(ctx))
        .withMessageContaining("scope does not match");
  }

  @Test
  void adminKeyPassesAdminOnlyEndpoint() {
    ContainerRequestContext ctx =
        contextWith("admin-key.admin-secret", noPathParams(), AdminResource.class);

    filter.filter(ctx);

    ArgumentCaptor<VectorStoreSecurityContext> captor =
        ArgumentCaptor.forClass(VectorStoreSecurityContext.class);
    verify(ctx).setSecurityContext(captor.capture());
    assertThat(captor.getValue().getUserPrincipal())
        .isEqualTo(new BucketScopedPrincipal("admin-key", null));
    assertThat(captor.getValue().isUserInRole("admin")).isTrue();
  }

  @Test
  void adminKeyPassesBucketScopedEndpoint() {
    ContainerRequestContext ctx =
        contextWith(
            "admin-key.admin-secret", bucket("demo"), BucketScopedResource.class);

    filter.filter(ctx);

    verify(ctx).setSecurityContext(org.mockito.ArgumentMatchers.any(VectorStoreSecurityContext.class));
  }

  @Test
  void matchingBucketKeyPassesBucketScopedEndpoint() {
    ContainerRequestContext ctx =
        contextWith(
            "demo-key.demo-secret", bucket("demo"), BucketScopedResource.class);

    filter.filter(ctx);

    ArgumentCaptor<VectorStoreSecurityContext> captor =
        ArgumentCaptor.forClass(VectorStoreSecurityContext.class);
    verify(ctx).setSecurityContext(captor.capture());
    BucketScopedPrincipal principal =
        (BucketScopedPrincipal) captor.getValue().getUserPrincipal();
    assertThat(principal.bucketId()).isEqualTo("demo");
    assertThat(principal.isAdmin()).isFalse();
  }

  @Test
  void publicManagementPathBypassesAuthentication() {
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getPath()).thenReturn("q/health");
    when(ctx.getUriInfo()).thenReturn(uriInfo);

    filter.resourceInfo = null;
    filter.filter(ctx);

    assertThat(apiKeys.touchCalls()).isEmpty();
  }

  @Test
  void successfulAuthenticationTouchesLastUsed() {
    ContainerRequestContext ctx =
        contextWith(
            "demo-key.demo-secret", bucket("demo"), BucketScopedResource.class);

    filter.filter(ctx);

    assertThat(apiKeys.touchCalls())
        .containsExactly(new InMemoryApiKeyRepository.TouchCall("demo-key", FROZEN_NOW));
  }

  private ContainerRequestContext contextWith(
      String apiKeyHeader,
      MultivaluedMap<String, String> pathParams,
      Class<?> resourceClass) {
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getHeaderString(ApiKeyAuthenticationFilter.HEADER)).thenReturn(apiKeyHeader);
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getPath()).thenReturn("v1/buckets");
    when(uriInfo.getPathParameters()).thenReturn(pathParams);
    when(ctx.getUriInfo()).thenReturn(uriInfo);
    when(ctx.getSecurityContext()).thenReturn(null);

    filter.resourceInfo = resourceInfoFor(resourceClass);
    return ctx;
  }

  private static ResourceInfo resourceInfoFor(Class<?> resourceClass) {
    ResourceInfo info = mock(ResourceInfo.class);
    try {
      Method op = resourceClass.getDeclaredMethod("op");
      when(info.getResourceClass()).thenAnswer(invocation -> resourceClass);
      when(info.getResourceMethod()).thenReturn(op);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
    return info;
  }

  private static MultivaluedMap<String, String> noPathParams() {
    return new MultivaluedHashMap<>();
  }

  private static MultivaluedMap<String, String> bucket(String bucketId) {
    MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
    map.add(ApiKeyAuthenticationFilter.BUCKET_PATH_PARAM, bucketId);
    return map;
  }

  @AdminOnly
  private static class AdminResource {
    public void op() {}
  }

  private static class BucketScopedResource {
    public void op() {}
  }
}
