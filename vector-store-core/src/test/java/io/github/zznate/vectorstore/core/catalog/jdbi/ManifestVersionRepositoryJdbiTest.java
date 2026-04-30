package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManifestVersionRepositoryJdbiTest {

  private CatalogTestFixture fixture;
  private ManifestVersionRepository manifests;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    BucketRepository buckets = new BucketRepositoryJdbi(fixture.jdbi());
    VectorIndexRepository indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    manifests = new ManifestVersionRepositoryJdbi(fixture.jdbi());

    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    buckets.create(Bucket.active("demo", "Demo", t));
    indexes.create(
        VectorIndex.active(
            "demo/products", "demo", "Products", 4, DistanceMetric.COSINE, "{}", t));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void findCurrentReturnsLatestVersion() {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    manifests.append(new ManifestVersion("demo/products", 1, "[]", base));
    manifests.append(
        new ManifestVersion("demo/products", 2, "[\"seg-001\"]", base.plusSeconds(10)));

    assertThat(manifests.findCurrent("demo/products"))
        .map(ManifestVersion::version)
        .hasValue(2);
  }

  @Test
  void findCurrentReturnsEmptyWhenNoManifest() {
    assertThat(manifests.findCurrent("demo/products")).isEmpty();
  }

  @Test
  void listByIndexReturnsVersionsAscending() {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    manifests.append(new ManifestVersion("demo/products", 2, "[]", base.plusSeconds(10)));
    manifests.append(new ManifestVersion("demo/products", 1, "[]", base));
    manifests.append(new ManifestVersion("demo/products", 3, "[]", base.plusSeconds(20)));

    assertThat(manifests.listByIndex("demo/products"))
        .extracting(ManifestVersion::version)
        .containsExactly(1, 2, 3);
  }
}
