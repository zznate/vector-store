package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeyRepositoryJdbiTest {

  private CatalogTestFixture fixture;
  private ApiKeyRepository apiKeys;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    BucketRepository buckets = new BucketRepositoryJdbi(fixture.jdbi());
    apiKeys = new ApiKeyRepositoryJdbi(fixture.jdbi());

    buckets.create(Bucket.active("demo", "Demo", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void adminKeyRoundTripsPreservingNullBucket() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    ApiKey admin = new ApiKey("admin-1", "hash-a", null, now, null);

    apiKeys.create(admin);

    ApiKey loaded = apiKeys.findById("admin-1").orElseThrow();
    assertThat(loaded).isEqualTo(admin);
    assertThat(loaded.isAdmin()).isTrue();
  }

  @Test
  void bucketScopedKeyRoundTrips() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    ApiKey scoped = new ApiKey("tenant-1", "hash-t", "demo", now, null);

    apiKeys.create(scoped);

    ApiKey loaded = apiKeys.findById("tenant-1").orElseThrow();
    assertThat(loaded).isEqualTo(scoped);
    assertThat(loaded.isAdmin()).isFalse();
  }

  @Test
  void touchLastUsedUpdatesTheTimestamp() {
    Instant createdAt = Instant.parse("2026-04-01T00:00:00Z");
    apiKeys.create(new ApiKey("k", "hash", null, createdAt, null));
    Instant lastUsed = Instant.parse("2026-04-17T12:00:00Z");

    apiKeys.touchLastUsed("k", lastUsed);

    assertThat(apiKeys.findById("k"))
        .map(ApiKey::lastUsedAt)
        .hasValue(lastUsed);
  }

  @Test
  void adminKeyExistsReflectsCurrentState() {
    assertThat(apiKeys.adminKeyExists()).isFalse();

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    apiKeys.create(new ApiKey("bucket-scoped", "hash", "demo", now, null));
    assertThat(apiKeys.adminKeyExists()).isFalse();

    apiKeys.create(new ApiKey("admin", "hash", null, now, null));
    assertThat(apiKeys.adminKeyExists()).isTrue();
  }

  @Test
  void deleteRemovesTheKey() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    apiKeys.create(new ApiKey("k", "hash", null, now, null));

    apiKeys.delete("k");

    assertThat(apiKeys.findById("k")).isEmpty();
    assertThat(apiKeys.list()).isEmpty();
  }
}
