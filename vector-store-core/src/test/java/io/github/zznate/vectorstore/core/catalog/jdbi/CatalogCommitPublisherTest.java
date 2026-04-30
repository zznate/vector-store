package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogCommitPublisherTest {

  private static final String INDEX_ID = "demo/products";

  private CatalogTestFixture fixture;
  private CatalogCommitPublisher publisher;
  private SegmentRepository segments;
  private ManifestVersionRepository manifests;
  private StagedTombstoneRepository staged;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    BucketRepository buckets = new BucketRepositoryJdbi(fixture.jdbi());
    VectorIndexRepository indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    segments = new SegmentRepositoryJdbi(fixture.jdbi());
    manifests = new ManifestVersionRepositoryJdbi(fixture.jdbi());
    staged = new StagedTombstoneRepositoryJdbi(fixture.jdbi());
    publisher = new CatalogCommitPublisher(fixture.jdbi());

    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    buckets.create(Bucket.active("demo", "Demo", t));
    indexes.create(
        VectorIndex.active(INDEX_ID, "demo", "Products", 4, DistanceMetric.COSINE, "{}", t));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void publishMutatesAllThreeTablesInOneTransaction() {
    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    segments.create(
        new Segment("seg-001", INDEX_ID, SegmentState.BUILDING, 10, 0L, "p/seg-001", t));
    staged.stage(INDEX_ID, List.of("u1", "u2"), t);

    publisher.publish(
        "seg-001",
        SegmentState.ACTIVE,
        2048L,
        new ManifestVersion(INDEX_ID, 1, "[\"seg-001\"]", t),
        INDEX_ID,
        Set.of("u1", "u2"));

    assertThat(segments.findById("seg-001"))
        .map(Segment::state)
        .hasValue(SegmentState.ACTIVE);
    assertThat(segments.findById("seg-001"))
        .map(Segment::bytes)
        .hasValue(2048L);
    assertThat(manifests.findCurrent(INDEX_ID))
        .map(ManifestVersion::version)
        .hasValue(1);
    assertThat(staged.snapshot(INDEX_ID)).isEmpty();
  }

  @Test
  void publishWithEmptyUnstageSetStillAdvancesSegmentAndManifest() {
    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    segments.create(
        new Segment("seg-002", INDEX_ID, SegmentState.BUILDING, 5, 0L, "p/seg-002", t));

    publisher.publish(
        "seg-002",
        SegmentState.ACTIVE,
        1024L,
        new ManifestVersion(INDEX_ID, 1, "[\"seg-002\"]", t),
        INDEX_ID,
        Set.of());

    assertThat(segments.findById("seg-002"))
        .map(Segment::state)
        .hasValue(SegmentState.ACTIVE);
    assertThat(manifests.findCurrent(INDEX_ID))
        .map(ManifestVersion::version)
        .hasValue(1);
  }

  @Test
  void manifestPkViolationRollsBackSegmentTransitionAndUnstage() {
    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    segments.create(
        new Segment("seg-003", INDEX_ID, SegmentState.BUILDING, 5, 0L, "p/seg-003", t));
    staged.stage(INDEX_ID, List.of("u1"), t);
    manifests.append(new ManifestVersion(INDEX_ID, 1, "[]", t));

    // Attempt to insert a second version=1 row — PK violation. The whole
    // publish call must roll back: segment still BUILDING, staging intact.
    assertThatThrownBy(
            () ->
                publisher.publish(
                    "seg-003",
                    SegmentState.ACTIVE,
                    512L,
                    new ManifestVersion(INDEX_ID, 1, "[\"seg-003\"]", t),
                    INDEX_ID,
                    Set.of("u1")))
        .isInstanceOf(RuntimeException.class);

    assertThat(segments.findById("seg-003"))
        .map(Segment::state)
        .hasValue(SegmentState.BUILDING);
    assertThat(staged.snapshot(INDEX_ID)).containsExactly("u1");
    assertThat(manifests.findCurrent(INDEX_ID))
        .map(ManifestVersion::version)
        .hasValue(1);
  }
}
