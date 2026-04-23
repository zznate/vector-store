package io.github.zznate.vectorstore.app.config;

import io.github.zznate.vectorstore.core.catalog.jdbi.ApiKeyRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.BucketRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.CatalogCommitPublisher;
import io.github.zznate.vectorstore.core.catalog.jdbi.ManifestVersionRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.SegmentRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.StagedTombstoneRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.jdbi.VectorIndexRepositoryJdbi;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.jdbi.v3.core.Jdbi;

/**
 * CDI producers for every catalog repository interface. The JDBI-backed
 * implementations live in {@code vector-store-core} but are intentionally
 * not annotated as CDI beans themselves — the app module owns the wiring.
 */
@ApplicationScoped
public class RepositoryProducers {

  @Produces
  @Singleton
  public BucketRepository bucketRepository(Jdbi jdbi) {
    return new BucketRepositoryJdbi(jdbi);
  }

  @Produces
  @Singleton
  public VectorIndexRepository vectorIndexRepository(Jdbi jdbi) {
    return new VectorIndexRepositoryJdbi(jdbi);
  }

  @Produces
  @Singleton
  public SegmentRepository segmentRepository(Jdbi jdbi) {
    return new SegmentRepositoryJdbi(jdbi);
  }

  @Produces
  @Singleton
  public ManifestVersionRepository manifestVersionRepository(Jdbi jdbi) {
    return new ManifestVersionRepositoryJdbi(jdbi);
  }

  @Produces
  @Singleton
  public ApiKeyRepository apiKeyRepository(Jdbi jdbi) {
    return new ApiKeyRepositoryJdbi(jdbi);
  }

  @Produces
  @Singleton
  public StagedTombstoneRepository stagedTombstoneRepository(Jdbi jdbi) {
    return new StagedTombstoneRepositoryJdbi(jdbi);
  }

  @Produces
  @Singleton
  public CatalogCommitPublisher catalogCommitPublisher(Jdbi jdbi) {
    return new CatalogCommitPublisher(jdbi);
  }
}
