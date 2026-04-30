package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import java.util.List;
import java.util.Optional;

public interface ManifestVersionRepository {

  ManifestVersion append(ManifestVersion version);

  Optional<ManifestVersion> findCurrent(String indexId);

  /**
   * Every manifest version recorded for {@code indexId}, ordered by
   * {@code version} ASC.
   *
   * <p>Caller invariant: <b>diagnostic / test-only</b>. The production
   * query path resolves the active manifest via
   * {@link #findCurrent(String)} (single-row probe) backed by
   * {@link io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache}.
   * The {@code manifest_version} table grows monotonically with every
   * commit — capped at 1024 rows in SQL as a safety net so accidental
   * production use of this method cannot OOM the JVM. A long-running
   * busy index needs version pruning (Phase 2+); see
   * {@code project_rest_data_management.md} in project memory.
   */
  List<ManifestVersion> listByIndex(String indexId);

  /**
   * Drop every manifest_version row owned by {@code indexId}.
   *
   * <p>Caller invariant: <b>retention sweep only</b>, during index hard
   * delete after segment cleanup. Returns the number of rows removed.
   */
  int deleteByIndex(String indexId);
}
