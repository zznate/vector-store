package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public class ManifestVersionRepositoryJdbi implements ManifestVersionRepository {

  private final Jdbi jdbi;

  @Inject
  public ManifestVersionRepositoryJdbi(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  public ManifestVersion append(ManifestVersion version) {
    jdbi.useExtension(ManifestVersionDao.class, dao -> dao.insert(version));
    return version;
  }

  @Override
  public Optional<ManifestVersion> findCurrent(String indexId) {
    return jdbi.withExtension(ManifestVersionDao.class, dao -> dao.findCurrent(indexId));
  }

  @Override
  public List<ManifestVersion> listByIndex(String indexId) {
    return jdbi.withExtension(ManifestVersionDao.class, dao -> dao.listByIndex(indexId));
  }

  @Override
  public int deleteByIndex(String indexId) {
    return jdbi.withExtension(ManifestVersionDao.class, dao -> dao.deleteByIndex(indexId));
  }
}
