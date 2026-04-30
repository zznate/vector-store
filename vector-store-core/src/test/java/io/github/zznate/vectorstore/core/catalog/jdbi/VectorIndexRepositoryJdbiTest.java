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
    buckets.create(new Bucket("demo", "Demo", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void createThenFindByIdReturnsSameRecordWithEnumAndJsonPreserved() {
    VectorIndex index =
        new VectorIndex(
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
    buckets.create(new Bucket("other", "Other", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    Instant t = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        new VectorIndex("demo/a", "demo", "A", 4, DistanceMetric.COSINE, "{}", t));
    indexes.create(
        new VectorIndex("demo/b", "demo", "B", 4, DistanceMetric.COSINE, "{}", t.plusSeconds(1)));
    indexes.create(
        new VectorIndex("other/c", "other", "C", 4, DistanceMetric.EUCLIDEAN, "{}", t));

    assertThat(indexes.listByBucket("demo"))
        .extracting(VectorIndex::indexId)
        .containsExactly("demo/a", "demo/b");
  }

  @Test
  void listAllReturnsEveryIndexAcrossBucketsOrderedByCreatedAt() {
    buckets.create(new Bucket("other", "Other", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    Instant t = Instant.parse("2026-04-01T00:00:00Z");
    indexes.create(
        new VectorIndex("demo/a", "demo", "A", 4, DistanceMetric.COSINE, "{}", t));
    indexes.create(
        new VectorIndex("other/c", "other", "C", 4, DistanceMetric.EUCLIDEAN, "{}", t.plusSeconds(1)));
    indexes.create(
        new VectorIndex("demo/b", "demo", "B", 4, DistanceMetric.COSINE, "{}", t.plusSeconds(2)));

    assertThat(indexes.listAll())
        .extracting(VectorIndex::indexId)
        .containsExactly("demo/a", "other/c", "demo/b");
  }

  @Test
  void deleteRemovesTheIndex() {
    indexes.create(
        new VectorIndex(
            "demo/x",
            "demo",
            "X",
            8,
            DistanceMetric.DOT_PRODUCT,
            "{}",
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    indexes.delete("demo/x");

    assertThat(indexes.findById("demo/x")).isEmpty();
  }
}
