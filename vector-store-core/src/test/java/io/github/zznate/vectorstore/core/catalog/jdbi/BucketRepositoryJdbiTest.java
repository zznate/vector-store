package io.github.zznate.vectorstore.core.catalog.jdbi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.testsupport.CatalogTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BucketRepositoryJdbiTest {

  private CatalogTestFixture fixture;
  private BucketRepository repository;

  @BeforeEach
  void setUp() {
    fixture = new CatalogTestFixture();
    repository = new BucketRepositoryJdbi(fixture.jdbi());
  }

  @AfterEach
  void tearDown() throws Exception {
    fixture.close();
  }

  @Test
  void createThenFindByIdReturnsSameRecord() {
    Bucket created =
        repository.create(
            Bucket.active("demo", "Demo Bucket", Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    assertThat(repository.findById("demo")).hasValue(created);
  }

  @Test
  void findByIdReturnsEmptyWhenAbsent() {
    assertThat(repository.findById("missing")).isEmpty();
  }

  @Test
  void listReturnsAllBucketsOrderedByCreatedAt() {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    repository.create(Bucket.active("b", "Second", base.plusSeconds(10)));
    repository.create(Bucket.active("a", "First", base));

    assertThat(repository.list())
        .extracting(Bucket::bucketId)
        .containsExactly("a", "b");
  }

  @Test
  void hardDeleteRemovesTheBucket() {
    repository.create(Bucket.active("demo", "Demo", Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    repository.hardDelete("demo");

    assertThat(repository.findById("demo")).isEmpty();
    assertThat(repository.findIncludingDeleted("demo")).isEmpty();
    assertThat(repository.list()).isEmpty();
  }

  @Test
  void softDeleteHidesFromActiveReadsButFindIncludingDeletedReturnsRow() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    repository.create(Bucket.active("demo", "Demo", created));
    Instant at = Instant.parse("2026-04-10T00:00:00Z");

    assertThat(repository.softDelete("demo", at)).isTrue();

    assertThat(repository.findById("demo")).isEmpty();
    assertThat(repository.list()).isEmpty();
    assertThat(repository.findIncludingDeleted("demo"))
        .hasValueSatisfying(
            row -> {
              assertThat(row.deletedAt()).isEqualTo(at);
              assertThat(row.isDeleted()).isTrue();
            });
  }

  @Test
  void softDeleteIsIdempotentReturnsFalseSecondTime() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    repository.create(Bucket.active("demo", "Demo", created));
    Instant first = Instant.parse("2026-04-10T00:00:00Z");
    Instant second = Instant.parse("2026-04-11T00:00:00Z");

    assertThat(repository.softDelete("demo", first)).isTrue();
    assertThat(repository.softDelete("demo", second)).isFalse();
    assertThat(repository.findIncludingDeleted("demo"))
        .hasValueSatisfying(row -> assertThat(row.deletedAt()).isEqualTo(first));
  }

  @Test
  void softDeleteOfMissingBucketReturnsFalse() {
    assertThat(repository.softDelete("missing", Instant.now())).isFalse();
  }

  @Test
  void restoreClearsDeletedAtAndReturnsRowToActiveReads() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    repository.create(Bucket.active("demo", "Demo", created));
    repository.softDelete("demo", Instant.parse("2026-04-10T00:00:00Z"));

    assertThat(repository.restore("demo")).isTrue();
    assertThat(repository.restore("demo")).isFalse();
    assertThat(repository.findById("demo")).isPresent();
  }

  @Test
  void restoreOfMissingBucketReturnsFalse() {
    assertThat(repository.restore("missing")).isFalse();
  }

  @Test
  void listSoftDeletedBeforeReturnsOnlyExpiredRows() {
    Instant created = Instant.parse("2026-04-01T00:00:00Z");
    repository.create(Bucket.active("a", "A", created));
    repository.create(Bucket.active("b", "B", created));
    repository.create(Bucket.active("c", "C", created));

    repository.softDelete("a", Instant.parse("2026-04-10T00:00:00Z"));
    repository.softDelete("b", Instant.parse("2026-04-15T00:00:00Z"));
    // c stays active

    Instant cutoff = Instant.parse("2026-04-12T00:00:00Z");
    assertThat(repository.listSoftDeletedBefore(cutoff))
        .extracting(Bucket::bucketId)
        .containsExactly("a");
  }
}
