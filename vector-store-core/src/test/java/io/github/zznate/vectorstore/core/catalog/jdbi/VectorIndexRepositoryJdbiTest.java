package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VectorIndexRepositoryJdbiTest {

  private CatalogTestFixture fixture;
  private BucketRepository buckets;
  private VectorIndexRepository indexes;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    buckets = new BucketRepositoryJdbi(fixture.jdbi());
    indexes = new VectorIndexRepositoryJdbi(fixture.jdbi());
    buckets.create(Bucket.active("demo", "Demo", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void createThenFindByIdReturnsSameRecordWithEnumAndJsonPreserved() {
    VectorIndex index =
        VectorIndex.active(
            "demo/products",
            "demo",
            "Products",
            1024,
            DistanceMetric.COSINE,
            "{\"m\":32,\"beamWidth\":200}",
            Instant.now().truncatedTo(ChronoUnit.MILLIS));

    VectorIndex created = indexes.create(index);

    assertThat(indexes.findById("demo/products")).hasValue(created);
  }

  @Test
  void listByBucketReturnsOnlyThatBucketsIndexes() {
    buckets.create(Bucket.active("other", "Other", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    Instant t = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/a", "demo", "A", 4, DistanceMetric.COSINE, "{}", t));
    indexes.create(
        VectorIndex.active(
            "demo/b", "demo", "B", 4, DistanceMetric.COSINE, "{}", t.plusSeconds(1)));
    indexes.create(
        VectorIndex.active("other/c", "other", "C", 4, DistanceMetric.EUCLIDEAN, "{}", t));

    assertThat(indexes.listByBucket("demo"))
        .extracting(VectorIndex::indexId)
        .containsExactly("demo/a", "demo/b");
  }

  @Test
  void listAllReturnsEveryIndexAcrossBucketsOrderedByCreatedAt() {
    buckets.create(Bucket.active("other", "Other", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    Instant t = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/a", "demo", "A", 4, DistanceMetric.COSINE, "{}", t));
    indexes.create(
        VectorIndex.active(
            "other/c", "other", "C", 4, DistanceMetric.EUCLIDEAN, "{}", t.plusSeconds(1)));
    indexes.create(
        VectorIndex.active(
            "demo/b", "demo", "B", 4, DistanceMetric.COSINE, "{}", t.plusSeconds(2)));

    assertThat(indexes.listAll())
        .extracting(VectorIndex::indexId)
        .containsExactly("demo/a", "other/c", "demo/b");
  }

  @Test
  void hardDeleteRemovesTheIndex() {
    indexes.create(
        VectorIndex.active(
            "demo/x",
            "demo",
            "X",
            8,
            DistanceMetric.DOT_PRODUCT,
            "{}",
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    indexes.hardDelete("demo/x");

    assertThat(indexes.findById("demo/x")).isEmpty();
    assertThat(indexes.findIncludingDeleted("demo/x")).isEmpty();
  }

  @Test
  void softDeleteHidesFromActiveReadsButFindIncludingDeletedReturnsRow() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/x", "demo", "X", 4, DistanceMetric.COSINE, "{}", created));
    Instant at = Instant.parse("2026-04-10T00:00:00Z");

    assertThat(indexes.softDelete("demo/x", at)).isTrue();

    assertThat(indexes.findById("demo/x")).isEmpty();
    assertThat(indexes.listByBucket("demo")).isEmpty();
    assertThat(indexes.listAll()).isEmpty();
    assertThat(indexes.findIncludingDeleted("demo/x"))
        .hasValueSatisfying(
            row -> {
              assertThat(row.deletedAt()).isEqualTo(at);
              assertThat(row.isDeleted()).isTrue();
            });
  }

  @Test
  void softDeleteIsIdempotentReturnsFalseSecondTime() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/x", "demo", "X", 4, DistanceMetric.COSINE, "{}", created));
    Instant first = Instant.parse("2026-04-10T00:00:00Z");
    Instant second = Instant.parse("2026-04-11T00:00:00Z");

    assertThat(indexes.softDelete("demo/x", first)).isTrue();
    assertThat(indexes.softDelete("demo/x", second)).isFalse();
    assertThat(indexes.findIncludingDeleted("demo/x"))
        .hasValueSatisfying(row -> assertThat(row.deletedAt()).isEqualTo(first));
  }

  @Test
  void restoreClearsDeletedAtAndReturnsRowToActiveReads() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/x", "demo", "X", 4, DistanceMetric.COSINE, "{}", created));
    indexes.softDelete("demo/x", Instant.parse("2026-04-10T00:00:00Z"));

    assertThat(indexes.restore("demo/x")).isTrue();
    assertThat(indexes.restore("demo/x")).isFalse();
    assertThat(indexes.findById("demo/x")).isPresent();
  }

  @Test
  void listSoftDeletedBeforeReturnsOnlyExpiredRows() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/a", "demo", "A", 4, DistanceMetric.COSINE, "{}", created));
    indexes.create(
        VectorIndex.active("demo/b", "demo", "B", 4, DistanceMetric.COSINE, "{}", created));
    indexes.create(
        VectorIndex.active("demo/c", "demo", "C", 4, DistanceMetric.COSINE, "{}", created));

    indexes.softDelete("demo/a", Instant.parse("2026-04-10T00:00:00Z"));
    indexes.softDelete("demo/b", Instant.parse("2026-04-15T00:00:00Z"));
    // demo/c stays active

    Instant cutoff = Instant.parse("2026-04-12T00:00:00Z");
    assertThat(indexes.listSoftDeletedBefore(cutoff))
        .extracting(VectorIndex::indexId)
        .containsExactly("demo/a");
  }

  @Test
  void countAnyByBucketIncludesSoftDeletedRows() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        VectorIndex.active("demo/a", "demo", "A", 4, DistanceMetric.COSINE, "{}", created));
    indexes.create(
        VectorIndex.active("demo/b", "demo", "B", 4, DistanceMetric.COSINE, "{}", created));

    indexes.softDelete("demo/a", Instant.parse("2026-04-10T00:00:00Z"));

    assertThat(indexes.countAnyByBucket("demo")).isEqualTo(2);
    assertThat(indexes.listByBucket("demo")).extracting(VectorIndex::indexId).containsExactly("demo/b");
  }
}
