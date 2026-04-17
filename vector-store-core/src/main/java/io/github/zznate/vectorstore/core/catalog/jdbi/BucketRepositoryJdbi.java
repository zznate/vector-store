package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import jakarta.inject.Inject;
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
  public List<Bucket> list() {
    return jdbi.withExtension(BucketDao.class, BucketDao::list);
  }

  @Override
  public void delete(String bucketId) {
    jdbi.useExtension(BucketDao.class, dao -> dao.delete(bucketId));
  }
}
