package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public class VectorIndexRepositoryJdbi implements VectorIndexRepository {

  private final Jdbi jdbi;

  @Inject
  public VectorIndexRepositoryJdbi(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  public VectorIndex create(VectorIndex index) {
    jdbi.useExtension(VectorIndexDao.class, dao -> dao.insert(index));
    return index;
  }

  @Override
  public Optional<VectorIndex> findById(String indexId) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.findById(indexId));
  }

  @Override
  public Optional<VectorIndex> findIncludingDeleted(String indexId) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.findIncludingDeleted(indexId));
  }

  @Override
  public List<VectorIndex> listByBucket(String bucketId) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.listByBucket(bucketId));
  }

  @Override
  public List<VectorIndex> listAll() {
    return jdbi.withExtension(VectorIndexDao.class, VectorIndexDao::listAll);
  }

  @Override
  public List<VectorIndex> listSoftDeletedBefore(Instant cutoff) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.listSoftDeletedBefore(cutoff));
  }

  @Override
  public int countAnyByBucket(String bucketId) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.countAnyByBucket(bucketId));
  }

  @Override
  public boolean softDelete(String indexId, Instant at) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.softDelete(indexId, at)) > 0;
  }

  @Override
  public boolean restore(String indexId) {
    return jdbi.withExtension(VectorIndexDao.class, dao -> dao.restore(indexId)) > 0;
  }

  @Override
  public void hardDelete(String indexId) {
    jdbi.useExtension(VectorIndexDao.class, dao -> dao.hardDelete(indexId));
  }
}
