package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public class BucketRepositoryJdbi implements BucketRepository {

  private final Jdbi jdbi;

  @Inject
  public BucketRepositoryJdbi(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  public Bucket create(Bucket bucket) {
    jdbi.useExtension(BucketDao.class, dao -> dao.insert(bucket));
    return bucket;
  }

  @Override
  public Optional<Bucket> findById(String bucketId) {
    return jdbi.withExtension(BucketDao.class, dao -> dao.findById(bucketId));
  }

  @Override
  public Optional<Bucket> findIncludingDeleted(String bucketId) {
    return jdbi.withExtension(BucketDao.class, dao -> dao.findIncludingDeleted(bucketId));
  }

  @Override
  public List<Bucket> list() {
    return jdbi.withExtension(BucketDao.class, BucketDao::list);
  }

  @Override
  public List<Bucket> listSoftDeletedBefore(Instant cutoff) {
    return jdbi.withExtension(BucketDao.class, dao -> dao.listSoftDeletedBefore(cutoff));
  }

  @Override
  public boolean softDelete(String bucketId, Instant at) {
    return jdbi.withExtension(BucketDao.class, dao -> dao.softDelete(bucketId, at)) > 0;
  }

  @Override
  public boolean restore(String bucketId) {
    return jdbi.withExtension(BucketDao.class, dao -> dao.restore(bucketId)) > 0;
  }

  @Override
  public void hardDelete(String bucketId) {
    jdbi.useExtension(BucketDao.class, dao -> dao.hardDelete(bucketId));
  }
}
