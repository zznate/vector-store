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
            new Bucket("demo", "Demo Bucket", Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    assertThat(repository.findById("demo")).hasValue(created);
  }

  @Test
  void findByIdReturnsEmptyWhenAbsent() {
    assertThat(repository.findById("missing")).isEmpty();
  }

  @Test
  void listReturnsAllBucketsOrderedByCreatedAt() {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    repository.create(new Bucket("b", "Second", base.plusSeconds(10)));
    repository.create(new Bucket("a", "First", base));

    assertThat(repository.list())
        .extracting(Bucket::bucketId)
        .containsExactly("a", "b");
  }

  @Test
  void deleteRemovesTheBucket() {
    repository.create(new Bucket("demo", "Demo", Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    repository.delete("demo");

    assertThat(repository.findById("demo")).isEmpty();
    assertThat(repository.list()).isEmpty();
  }
}
