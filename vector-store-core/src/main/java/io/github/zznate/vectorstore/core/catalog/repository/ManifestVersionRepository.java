package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import java.util.List;
import java.util.Optional;

public interface ManifestVersionRepository {

  ManifestVersion append(ManifestVersion version);

  Optional<ManifestVersion> findCurrent(String indexId);

  List<ManifestVersion> listByIndex(String indexId);
}
