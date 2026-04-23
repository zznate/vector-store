package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
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

class StagedTombstoneRepositoryJdbiTest {

  private static final String INDEX_ID = "demo/products";

  private CatalogTestFixture fixture;
  private StagedTombstoneRepository staged;
  private VectorIndexRepository indexes;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    BucketRepository buckets = new BucketRepositoryJdbi(fixture.jdbi());
    indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    staged = new StagedTombstoneRepositoryJdbi(fixture.jdbi());

    Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    buckets.create(new Bucket("demo", "Demo", t));
    indexes.create(new VectorIndex(INDEX_ID, "demo", "Products", 4, DistanceMetric.COSINE, "{}", t));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void stageAndSnapshotRoundTripPreservesIds() {
    staged.stage(INDEX_ID, List.of("u1", "u2", "u3"), Instant.now());

    assertThat(staged.snapshot(INDEX_ID)).containsExactlyInAnyOrder("u1", "u2", "u3");
  }

  @Test
  void stageIsIdempotentAcrossRepeatedCalls() {
    Instant firstStage = Instant.parse("2026-04-01T00:00:00Z");
    staged.stage(INDEX_ID, List.of("u1", "u2"), firstStage);
    staged.stage(INDEX_ID, List.of("u2", "u3"), Instant.parse("2026-04-02T00:00:00Z"));

    assertThat(staged.snapshot(INDEX_ID)).containsExactlyInAnyOrder("u1", "u2", "u3");
    assertThat(staged.count(INDEX_ID)).isEqualTo(3);
  }

  @Test
  void unstageRemovesOnlyTheGivenIds() {
    staged.stage(INDEX_ID, List.of("u1", "u2", "u3", "u4"), Instant.now());

    staged.unstage(INDEX_ID, List.of("u2", "u4"));

    assertThat(staged.snapshot(INDEX_ID)).containsExactlyInAnyOrder("u1", "u3");
  }

  @Test
  void unstageEmptyCollectionIsANoop() {
    staged.stage(INDEX_ID, List.of("u1"), Instant.now());

    staged.unstage(INDEX_ID, Set.of());

    assertThat(staged.snapshot(INDEX_ID)).containsExactly("u1");
  }

  @Test
  void stageEmptyCollectionIsANoop() {
    staged.stage(INDEX_ID, Set.of(), Instant.now());

    assertThat(staged.snapshot(INDEX_ID)).isEmpty();
    assertThat(staged.count(INDEX_ID)).isZero();
  }

  @Test
  void countReflectsCurrentState() {
    assertThat(staged.count(INDEX_ID)).isZero();

    staged.stage(INDEX_ID, List.of("u1", "u2", "u3"), Instant.now());
    assertThat(staged.count(INDEX_ID)).isEqualTo(3);

    staged.unstage(INDEX_ID, List.of("u1"));
    assertThat(staged.count(INDEX_ID)).isEqualTo(2);
  }

  @Test
  void isStagedReflectsMembership() {
    staged.stage(INDEX_ID, List.of("u1", "u2"), Instant.now());

    assertThat(staged.isStaged(INDEX_ID, "u1")).isTrue();
    assertThat(staged.isStaged(INDEX_ID, "u2")).isTrue();
    assertThat(staged.isStaged(INDEX_ID, "u3")).isFalse();
  }

  @Test
  void snapshotForUnknownIndexReturnsEmptySet() {
    assertThat(staged.snapshot("demo/missing")).isEmpty();
    assertThat(staged.count("demo/missing")).isZero();
  }

  @Test
  void indexDeletionCascadesTheStagingRows() {
    staged.stage(INDEX_ID, List.of("u1", "u2"), Instant.now());
    assertThat(staged.count(INDEX_ID)).isEqualTo(2);

    indexes.delete(INDEX_ID);

    assertThat(staged.snapshot(INDEX_ID)).isEmpty();
    assertThat(staged.count(INDEX_ID)).isZero();
  }
}
