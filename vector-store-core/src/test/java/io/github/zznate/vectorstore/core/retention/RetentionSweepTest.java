package io.github.zznate.vectorstore.core.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.zznate.vectorstore.core.catalog.jdbi.BucketRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.ManifestVersionRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.SegmentRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.VectorIndexRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestResolver;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetentionSweepTest {

  private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");
  private static final Duration WINDOW = Duration.ofDays(7);

  private CatalogTestFixture fixture;
  private BucketRepository buckets;
  private VectorIndexRepository indexes;
  private SegmentRepository segments;
  private ManifestVersionRepository manifests;
  private RecordingSegmentStore segmentStore;
  private ManifestCache manifestCache;
  private RetentionSweep sweep;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    buckets = new BucketRepositoryJdbi(fixture.jdbi());
    indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    segments = new SegmentRepositoryJdbi(fixture.jdbi());
    manifests = new ManifestVersionRepositoryJdbi(fixture.jdbi());
    segmentStore = new RecordingSegmentStore();
    manifestCache =
        new ManifestCache(
            new ManifestResolver(manifests, segments),
            new SimpleMeterRegistry(),
            ManifestCache.DEFAULT_MAX_ENTRIES,
            ManifestCache.DEFAULT_VERSION_TTL_NANOS);
    sweep =
        new RetentionSweep(
            buckets,
            indexes,
            segments,
            manifests,
            segmentStore,
            manifestCache,
            new TestRetentionConfig(true, Duration.ofMinutes(15), WINDOW, WINDOW),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void emptyCatalogIsNoop() {
    assertThat(sweep.runOnce()).isEqualTo(new RetentionSweep.SweepResult(0, 0));
  }

  @Test
  void softDeletedIndexPastRetentionIsHardDeletedWithCascade() {
    seedBucket("demo");
    seedIndex("demo/products", "demo");
    seedSegment("seg-1", "demo/products", "demo/products/seg-1");
    seedSegment("seg-2", "demo/products", "demo/products/seg-2");
    seedManifest("demo/products", 1);

    Instant deletedAt = NOW.minus(WINDOW).minus(Duration.ofMinutes(1));
    indexes.softDelete("demo/products", deletedAt);

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.indexesHardDeleted()).isEqualTo(1);
    assertThat(indexes.findIncludingDeleted("demo/products")).isEmpty();
    assertThat(segments.listByIndex("demo/products")).isEmpty();
    assertThat(manifests.listByIndex("demo/products")).isEmpty();
    assertThat(segmentStore.deletedPrefixes)
        .containsExactlyInAnyOrder("demo/products/seg-1", "demo/products/seg-2");
  }

  @Test
  void softDeletedIndexInsideRetentionIsLeftAlone() {
    seedBucket("demo");
    seedIndex("demo/products", "demo");

    indexes.softDelete("demo/products", NOW.minus(Duration.ofDays(3)));

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.indexesHardDeleted()).isZero();
    assertThat(indexes.findIncludingDeleted("demo/products")).isPresent();
    assertThat(segmentStore.deletedPrefixes).isEmpty();
  }

  @Test
  void softDeletedBucketWithActiveChildIsSkipped() {
    seedBucket("demo");
    seedIndex("demo/products", "demo");
    buckets.softDelete("demo", NOW.minus(WINDOW).minus(Duration.ofMinutes(1)));

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.bucketsHardDeleted()).isZero();
    assertThat(buckets.findIncludingDeleted("demo")).isPresent();
  }

  @Test
  void softDeletedBucketWithSoftDeletedChildIsSkippedUntilChildExpires() {
    seedBucket("demo");
    seedIndex("demo/products", "demo");
    indexes.softDelete("demo/products", NOW.minus(Duration.ofDays(3)));
    buckets.softDelete("demo", NOW.minus(WINDOW).minus(Duration.ofMinutes(1)));

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.indexesHardDeleted())
        .as("child index inside retention, not yet eligible")
        .isZero();
    assertThat(result.bucketsHardDeleted())
        .as("bucket waits for children to hard-delete first")
        .isZero();
    assertThat(buckets.findIncludingDeleted("demo")).isPresent();
  }

  @Test
  void softDeletedBucketAndChildBothPastRetentionAreSweptInOneIteration() {
    seedBucket("demo");
    seedIndex("demo/products", "demo");
    Instant past = NOW.minus(WINDOW).minus(Duration.ofMinutes(1));
    indexes.softDelete("demo/products", past);
    buckets.softDelete("demo", past);

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.indexesHardDeleted()).isEqualTo(1);
    assertThat(result.bucketsHardDeleted()).isEqualTo(1);
    assertThat(buckets.findIncludingDeleted("demo")).isEmpty();
    assertThat(indexes.findIncludingDeleted("demo/products")).isEmpty();
  }

  @Test
  void activeRowsAreNeverTouched() {
    seedBucket("demo");
    seedIndex("demo/products", "demo");
    seedSegment("seg-1", "demo/products", "demo/products/seg-1");

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result).isEqualTo(new RetentionSweep.SweepResult(0, 0));
    assertThat(buckets.findById("demo")).isPresent();
    assertThat(indexes.findById("demo/products")).isPresent();
    assertThat(segments.findById("seg-1")).isPresent();
    assertThat(segmentStore.deletedPrefixes).isEmpty();
  }

  // ---- helpers --------------------------------------------------------

  private void seedBucket(String bucketId) {
    buckets.create(Bucket.active(bucketId, bucketId, NOW.minus(Duration.ofDays(30))));
  }

  private void seedIndex(String indexId, String bucketId) {
    indexes.create(
        VectorIndex.active(
            indexId,
            bucketId,
            indexId,
            4,
            DistanceMetric.COSINE,
            "{}",
            NOW.minus(Duration.ofDays(30))));
  }

  private void seedSegment(String segmentId, String indexId, String objectPrefix) {
    segments.create(
        new Segment(
            segmentId,
            indexId,
            SegmentState.ACTIVE,
            10,
            1024,
            objectPrefix,
            NOW.minus(Duration.ofDays(30))));
  }

  private void seedManifest(String indexId, int version) {
    manifests.append(new ManifestVersion(indexId, version, "[]", NOW.minus(Duration.ofDays(30))));
  }

  /** Records every {@code deletePrefix} call; other ops throw if invoked. */
  private static final class RecordingSegmentStore implements SegmentStore {
    final List<String> deletedPrefixes = new ArrayList<>();

    @Override
    public URI publish(BuiltSegment local, String objectPrefix) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public ReaderSupplier openGraph(Segment segment) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public InputStream openSidecar(Segment segment, String fileName) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void putSidecar(Segment segment, String fileName, byte[] content) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void deletePrefix(String objectPrefix) {
      deletedPrefixes.add(objectPrefix);
    }
  }

  /** Hand-rolled config so the test does not depend on SmallRye proxy generation. */
  private record TestRetentionConfig(
      boolean enabled, Duration interval, Duration indexWindow, Duration bucketWindow)
      implements RetentionConfig {

    @Override
    public IndexRetention index() {
      return () -> indexWindow;
    }

    @Override
    public BucketRetention bucket() {
      return () -> bucketWindow;
    }
  }
}
