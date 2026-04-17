package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SegmentRepositoryJdbiTest {

  private CatalogTestFixture fixture;
  private SegmentRepository segments;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    BucketRepository buckets = new BucketRepositoryJdbi(fixture.jdbi());
    VectorIndexRepository indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    segments = new SegmentRepositoryJdbi(fixture.jdbi());

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
  void createThenFindByIdReturnsSameRecord() {
    Segment segment =
        new Segment(
            "seg-001",
            "demo/products",
            SegmentState.BUILDING,
            0,
            0,
            "vectorstore/demo/products/segments/seg-001",
            Instant.now().truncatedTo(ChronoUnit.MILLIS));

    Segment created = segments.create(segment);

    assertThat(segments.findById("seg-001")).hasValue(created);
  }

  @Test
  void listByIndexReturnsInsertionOrder() {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    segments.create(
        new Segment("a", "demo/products", SegmentState.ACTIVE, 100, 1024, "p/a", base));
    segments.create(
        new Segment(
            "b", "demo/products", SegmentState.ACTIVE, 200, 2048, "p/b", base.plusSeconds(1)));

    assertThat(segments.listByIndex("demo/products"))
        .extracting(Segment::segmentId)
        .containsExactly("a", "b");
  }

  @Test
  void updateStateTransitionsTheSegment() {
    segments.create(
        new Segment(
            "seg-001",
            "demo/products",
            SegmentState.BUILDING,
            0,
            0,
            "p",
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    segments.updateState("seg-001", SegmentState.ACTIVE);

    assertThat(segments.findById("seg-001"))
        .map(Segment::state)
        .hasValue(SegmentState.ACTIVE);
  }

  @Test
  void deleteRemovesTheSegment() {
    segments.create(
        new Segment(
            "seg-001",
            "demo/products",
            SegmentState.BUILDING,
            0,
            0,
            "p",
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    segments.delete("seg-001");

    assertThat(segments.findById("seg-001")).isEmpty();
  }
}
