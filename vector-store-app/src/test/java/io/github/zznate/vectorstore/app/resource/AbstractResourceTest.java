package io.github.zznate.vectorstore.app.resource;

import io.github.zznate.vectorstore.api.auth.PasswordHasher;
import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import java.time.Clock;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for {@code @QuarkusTest} resource-level component tests. Truncates
 * every catalog table before each test and seeds three API keys the subclass
 * assertions rely on.
 *
 * <p>Subclasses add their own {@code @QuarkusTest} annotation.
 */
abstract class AbstractResourceTest {

  protected static final String ADMIN_TOKEN = "admin-test.admin-secret";
  protected static final String DEMO_TOKEN = "demo-test.demo-secret";
  protected static final String OTHER_TOKEN = "other-test.other-secret";

  protected static final String DEMO_BUCKET = "demo";
  protected static final String OTHER_BUCKET = "other";

  @Inject Jdbi jdbi;
  @Inject ApiKeyRepository apiKeys;
  @Inject PasswordHasher hasher;
  @Inject Clock clock;

  @BeforeAll
  static void disableRestAssuredUrlEncoding() {
    // RestAssured (via Apache HttpClient) percent-encodes ':' to '%3A' in
    // path segments. The server does not decode reserved sub-delims before
    // route matching (RFC 3986), so templates with a literal ':' verb —
    // e.g. "/vectors:put", ":commit" — fail to match. Disabling client-side
    // URL encoding keeps our URL shape consistent with what real clients
    // (curl, browsers, most HTTP libraries) send on the wire. Every path
    // used in these tests is already RFC 3986-safe.
    RestAssured.urlEncodingEnabled = false;
  }

  @BeforeEach
  void resetCatalog() {
    jdbi.useHandle(
        h -> {
          h.execute("DELETE FROM manifest_version");
          h.execute("DELETE FROM segment");
          h.execute("DELETE FROM vector_index");
          h.execute("DELETE FROM vector_bucket");
          h.execute("DELETE FROM api_key");
        });
    apiKeys.create(
        new ApiKey("admin-test", hasher.hash("admin-secret"), null, clock.instant(), null));
    apiKeys.create(
        new ApiKey(
            "demo-test", hasher.hash("demo-secret"), DEMO_BUCKET, clock.instant(), null));
    apiKeys.create(
        new ApiKey(
            "other-test", hasher.hash("other-secret"), OTHER_BUCKET, clock.instant(), null));
  }
}
