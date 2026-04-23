package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

public class StagedTombstoneRepositoryJdbi implements StagedTombstoneRepository {

  private final Jdbi jdbi;

  @Inject
  public StagedTombstoneRepositoryJdbi(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  public void stage(String indexId, Collection<String> userIds, Instant stagedAt) {
    if (userIds.isEmpty()) {
      return;
    }
    jdbi.useExtension(
        StagedTombstoneDao.class, dao -> dao.stage(indexId, userIds, stagedAt));
  }

  @Override
  public void unstage(String indexId, Collection<String> userIds) {
    if (userIds.isEmpty()) {
      return;
    }
    jdbi.useExtension(StagedTombstoneDao.class, dao -> dao.unstage(indexId, userIds));
  }

  @Override
  public Set<String> snapshot(String indexId) {
    return Set.copyOf(
        jdbi.withExtension(StagedTombstoneDao.class, dao -> dao.snapshot(indexId)));
  }

  @Override
  public boolean isStaged(String indexId, String userId) {
    return jdbi
        .withExtension(StagedTombstoneDao.class, dao -> dao.existsStaged(indexId, userId))
        .isPresent();
  }

  @Override
  public int count(String indexId) {
    return jdbi.withExtension(StagedTombstoneDao.class, dao -> dao.count(indexId));
  }
}
