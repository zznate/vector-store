package io.github.zznate.vectorstore.core.retention;

import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the per-iteration retention cleanup. Pure-Java; the Quarkus
 * {@code @Scheduled} binding lives in {@code vector-store-app} so this
 * class is unit-testable without the CDI container or a scheduler.
 *
 * <p>Cascade ordering on each iteration:
 *
 * <ol>
 *   <li>For every soft-deleted index whose retention has elapsed:
 *       drop object-store data per segment, then catalog rows
 *       (segments, manifest_versions, the index row itself —
 *       staged_tombstone cascades from the FK).
 *   <li>For every soft-deleted bucket whose retention has elapsed
 *       <em>and</em> has zero remaining child indexes (in any state):
 *       hard-delete the bucket row.
 * </ol>
 *
 * <p>The two passes are sequenced so a bucket whose retention expires
 * at the same moment as its last child index can be cleaned up in a
 * single iteration: indexes go first, leaving the bucket childless and
 * eligible.
 *
 * <p>Crash safety: object-store cleanup runs <b>before</b> catalog
 * mutation. A JVM crash between the two leaves orphan files but the
 * catalog still references the index — the next sweep iteration retries
 * the (idempotent) prefix delete and finishes the catalog cascade. The
 * inverse failure mode (catalog gone, files referenced) is impossible
 * because we never delete catalog rows first.
 */
public class RetentionSweep {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionSweep.class);

  private final BucketRepository buckets;
  private final VectorIndexRepository indexes;
  private final SegmentRepository segments;
  private final ManifestVersionRepository manifests;
  private final SegmentStore segmentStore;
  private final ManifestCache manifestCache;
  private final RetentionConfig config;
  private final Clock clock;

  @SuppressWarnings("PMD.ExcessiveParameterList")
  public RetentionSweep(
      BucketRepository buckets,
      VectorIndexRepository indexes,
      SegmentRepository segments,
      ManifestVersionRepository manifests,
      SegmentStore segmentStore,
      ManifestCache manifestCache,
      RetentionConfig config,
      Clock clock) {
    this.buckets = buckets;
    this.indexes = indexes;
    this.segments = segments;
    this.manifests = manifests;
    this.segmentStore = segmentStore;
    this.manifestCache = manifestCache;
    this.config = config;
    this.clock = clock;
  }

  /**
   * Single pass over expired soft-deleted rows. Returns counts so a
   * caller (scheduler binding, IT, ad-hoc admin trigger) can log a
   * digest. Safe to call repeatedly; the per-method idempotency guards
   * mean a duplicate call is at worst a no-op.
   */
  public SweepResult runOnce() {
    Instant now = clock.instant();
    int indexesSwept = sweepIndexes(now);
    int bucketsSwept = sweepBuckets(now);
    if ((indexesSwept | bucketsSwept) != 0 && LOG.isInfoEnabled()) {
      LOG.info(
          "retention sweep complete: indexes={} buckets={} now={}",
          indexesSwept,
          bucketsSwept,
          now);
    }
    return new SweepResult(indexesSwept, bucketsSwept);
  }

  private int sweepIndexes(Instant now) {
    Instant cutoff = now.minus(config.index().window());
    List<VectorIndex> expired = indexes.listSoftDeletedBefore(cutoff);
    int swept = 0;
    for (VectorIndex idx : expired) {
      try {
        hardDeleteIndex(idx);
        swept++;
      } catch (RuntimeException e) {
        // Don't poison the rest of the sweep if one index fails;
        // log and continue. The next iteration retries.
        if (LOG.isWarnEnabled()) {
          LOG.warn("retention sweep failed to hard-delete index {}", idx.indexId(), e);
        }
      }
    }
    return swept;
  }

  private int sweepBuckets(Instant now) {
    Instant cutoff = now.minus(config.bucket().window());
    List<Bucket> expired = buckets.listSoftDeletedBefore(cutoff);
    int swept = 0;
    for (Bucket bucket : expired) {
      if (indexes.countAnyByBucket(bucket.bucketId()) > 0) {
        // Children still exist (any state). Wait for them to expire
        // and a future iteration to clean up.
        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "retention sweep deferred bucket {} hard-delete: still has child indexes",
              bucket.bucketId());
        }
        continue;
      }
      try {
        buckets.hardDelete(bucket.bucketId());
        swept++;
      } catch (RuntimeException e) {
        if (LOG.isWarnEnabled()) {
          LOG.warn(
              "retention sweep failed to hard-delete bucket {}", bucket.bucketId(), e);
        }
      }
    }
    return swept;
  }

  private void hardDeleteIndex(VectorIndex idx) {
    String indexId = idx.indexId();
    // Object-store first. Each segment's prefix is the canonical root.
    for (Segment segment : segments.listByIndex(indexId)) {
      try {
        segmentStore.deletePrefix(segment.objectPrefix());
      } catch (IOException e) {
        throw new UncheckedIOException(
            "retention sweep: failed to delete object-store prefix " + segment.objectPrefix(), e);
      }
    }
    // Catalog rows in dependency order. staged_tombstone cascades from
    // the index row via the FK declared in V2.
    int segmentRows = segments.deleteByIndex(indexId);
    int manifestRows = manifests.deleteByIndex(indexId);
    indexes.hardDelete(indexId);
    manifestCache.invalidate(indexId);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "retention sweep hard-deleted index {} (segments={} manifests={})",
          indexId,
          segmentRows,
          manifestRows);
    }
  }

  /** Per-iteration count digest. */
  public record SweepResult(int indexesHardDeleted, int bucketsHardDeleted) {}
}
