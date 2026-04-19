package io.github.zznate.vectorstore.core.catalog.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.zznate.vectorstore.core.catalog.jdbi.BucketRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.ManifestVersionRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.SegmentRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.VectorIndexRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManifestResolverTest {

  private CatalogTestFixture fixture;
  private ManifestResolver resolver;
  private ManifestVersionRepository manifests;
  private SegmentRepository segments;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    var buckets = new BucketRepositoryJdbi(fixture.jdbi());
    var indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    segments = new SegmentRepositoryJdbi(fixture.jdbi());
    manifests = new ManifestVersionRepositoryJdbi(fixture.jdbi());
    resolver = new ManifestResolver(manifests, segments);

    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    buckets.create(new Bucket("demo", "Demo", t));
    indexes.create(
        new VectorIndex("demo/products", "demo", "Products", 4, DistanceMetric.COSINE, "{}", t));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void activeSegmentsIsEmptyWhenNoManifestExists() {
    assertThat(resolver.activeSegments("demo/products")).isEmpty();
    assertThat(resolver.currentVersion("demo/products")).isEmpty();
  }

  @Test
  void activeSegmentsResolvesLatestManifestInOrder() {
    Instant t = Instant.parse("2026-04-20T00:00:00Z");
    segments.create(new Segment("seg-a", "demo/products", SegmentState.ACTIVE, 100, 1024, "p/a", t));
    segments.create(new Segment("seg-b", "demo/products", SegmentState.ACTIVE, 200, 2048, "p/b", t));
    segments.create(new Segment("seg-c", "demo/products", SegmentState.ACTIVE, 300, 3072, "p/c", t));

    manifests.append(
        new ManifestVersion("demo/products", 1, "[\"seg-a\"]", t.plusSeconds(10)));
    manifests.append(
        new ManifestVersion(
            "demo/products", 2, "[\"seg-a\",\"seg-b\",\"seg-c\"]", t.plusSeconds(20)));

    assertThat(resolver.activeSegments("demo/products"))
        .extracting(Segment::segmentId)
        .containsExactly("seg-a", "seg-b", "seg-c");
    assertThat(resolver.currentVersion("demo/products")).hasValue(2);
  }

  @Test
  void missingSegmentReferencesAreSilentlyDropped() {
    Instant t = Instant.parse("2026-04-20T00:00:00Z");
    segments.create(new Segment("seg-a", "demo/products", SegmentState.ACTIVE, 1, 1, "p/a", t));
    manifests.append(
        new ManifestVersion(
            "demo/products", 1, "[\"seg-a\",\"ghost\"]", t.plusSeconds(1)));

    assertThat(resolver.activeSegments("demo/products"))
        .extracting(Segment::segmentId)
        .containsExactly("seg-a");
  }

  @Test
  void malformedSegmentIdsJsonRaisesIllegalState() {
    Instant t = Instant.parse("2026-04-20T00:00:00Z");
    manifests.append(new ManifestVersion("demo/products", 1, "not-json", t));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> resolver.activeSegments("demo/products"))
        .withMessageContaining("Malformed segment_ids JSON");
  }
}
