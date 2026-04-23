package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import jakarta.inject.Inject;
import java.util.Collection;
import org.jdbi.v3.core.Jdbi;

/**
 * Atomic finaliser for a successful commit: in a single JDBI transaction,
 * flips the segment row to its published state, appends the new
 * manifest_version, and clears the staged tombstones that are rolling into
 * this commit. Either all three mutations land or none do — staging and
 * manifest are never out of agreement.
 *
 * <p>Lives in the {@code catalog/jdbi} package so it can reach the package-
 * private DAO interfaces and attach them to a shared {@link
 * org.jdbi.v3.core.Handle}. Consumers depend on this class rather than on
 * the individual repositories when atomicity matters; reads and
 * single-mutation writes continue to go through the repositories.
 */
public class CatalogCommitPublisher {

  private final Jdbi jdbi;

  @Inject
  public CatalogCommitPublisher(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * Run the three mutations atomically. {@code unstageIds} may be empty, in
   * which case the staged-tombstone delete is skipped.
   */
  public void publish(
      String segmentId,
      SegmentState state,
      long bytes,
      ManifestVersion manifestVersion,
      String indexId,
      Collection<String> unstageIds) {
    jdbi.useTransaction(
        handle -> {
          handle.attach(SegmentDao.class).updateStateAndBytes(segmentId, state, bytes);
          handle.attach(ManifestVersionDao.class).insert(manifestVersion);
          if (!unstageIds.isEmpty()) {
            handle.attach(StagedTombstoneDao.class).unstage(indexId, unstageIds);
          }
        });
  }
}
