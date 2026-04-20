package io.github.zznate.vectorstore.engine.commit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestResolver;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentIds;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.engine.buffer.BufferSnapshot;
import io.github.zznate.vectorstore.engine.buffer.WriteBuffer;
import io.github.zznate.vectorstore.engine.build.SegmentBuilder;
import io.github.zznate.vectorstore.engine.search.Searcher;
import io.github.zznate.vectorstore.engine.tombstone.InMemoryTombstones;
import io.github.zznate.vectorstore.metadata.sidecar.SidecarLoader;
import io.github.zznate.vectorstore.metadata.sidecar.TombstoneSidecar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a single commit for one index: buffer snapshot → BUILDING
 * catalog row → build on disk → publish via {@link SegmentStore} → flip to
 * ACTIVE → append manifest_version.
 *
 * <p>Every failure path retires the segment row (state = {@code RETIRED})
 * rather than deleting it, so the catalog retains an audit trail, and
 * increments the {@code vectorstore.commit.failures} counter tagged by the
 * phase the failure occurred in: {@code build}, {@code publish}, or
 * {@code catalog}. The counter is intended to feed a later alert.
 *
 * <p>Concurrent commits for the same index are serialised by a per-index
 * {@link ReentrantLock}. Commits for different indexes never contend.
 */
@ApplicationScoped
public class CommitCoordinator {

  /** Counter tag values for {@code vectorstore.commit.failures}. */
  public static final String PHASE_BUILD = "build";

  public static final String PHASE_PUBLISH = "publish";
  public static final String PHASE_CATALOG = "catalog";
  public static final String PHASE_TOMBSTONES = "tombstones";

  private static final Logger LOG = LoggerFactory.getLogger(CommitCoordinator.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final WriteBuffer writeBuffer;
  private final SegmentBuilder builder;
  private final SegmentStore segmentStore;
  private final SegmentRepository segments;
  private final ManifestVersionRepository manifestVersions;
  private final ManifestResolver manifestResolver;
  private final InMemoryTombstones tombstones;
  private final Searcher searcher;
  private final SidecarLoader sidecarLoader;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  private final ConcurrentHashMap<String, ReentrantLock> perIndexLocks = new ConcurrentHashMap<>();

  @Inject
  public CommitCoordinator(
      WriteBuffer writeBuffer,
      SegmentBuilder builder,
      SegmentStore segmentStore,
      SegmentRepository segments,
      ManifestVersionRepository manifestVersions,
      ManifestResolver manifestResolver,
      InMemoryTombstones tombstones,
      Searcher searcher,
      SidecarLoader sidecarLoader,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.writeBuffer = writeBuffer;
    this.builder = builder;
    this.segmentStore = segmentStore;
    this.segments = segments;
    this.manifestVersions = manifestVersions;
    this.manifestResolver = manifestResolver;
    this.tombstones = tombstones;
    this.searcher = searcher;
    this.sidecarLoader = sidecarLoader;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Commit the current write buffer for {@code index} to a fresh segment.
   * Returns the committed outcome on success.
   *
   * @throws EmptyCommitException if the buffer was empty at snapshot time.
   * @throws CommitFailedException if any build, publish, or catalog step
   *     fails. The segment row (if created) is left in {@code RETIRED}
   *     state and the {@code vectorstore.commit.failures} counter is
   *     incremented with the phase tag.
   */
  public CommitOutcome commit(VectorIndex index) throws CommitFailedException {
    ReentrantLock lock = perIndexLocks.computeIfAbsent(index.indexId(), k -> new ReentrantLock());
    lock.lock();
    try {
      return commitLocked(index);
    } finally {
      lock.unlock();
    }
  }

  private CommitOutcome commitLocked(VectorIndex index) throws CommitFailedException {
    BufferSnapshot snapshot = writeBuffer.snapshotAndClear(index.indexId());
    if (snapshot.isEmpty()) {
      throw new EmptyCommitException(index.indexId());
    }

    IndexBuildParams params = IndexBuildParams.fromJson(index.engineParams());
    String segmentId = SegmentIds.newSegmentId();
    String objectPrefix = objectPrefixFor(index, segmentId);

    segments.create(
        new Segment(
            segmentId,
            index.indexId(),
            SegmentState.BUILDING,
            snapshot.size(),
            0L,
            objectPrefix,
            clock.instant()));

    BuiltSegment built;
    try {
      built = builder.build(segmentId, snapshot, index.dimension(), index.metric(), params);
    } catch (Exception e) {
      failed(segmentId, PHASE_BUILD, e);
      throw new CommitFailedException(PHASE_BUILD, e);
    }

    try {
      segmentStore.publish(built, objectPrefix);
    } catch (Exception e) {
      failed(segmentId, PHASE_PUBLISH, e);
      throw new CommitFailedException(PHASE_PUBLISH, e);
    }

    int nextVersion;
    try {
      segments.updateStateAndBytes(segmentId, SegmentState.ACTIVE, built.bytes());
      nextVersion = manifestResolver.currentVersion(index.indexId()).orElse(0) + 1;
      List<String> segmentIds =
          new ArrayList<>(
              manifestResolver.activeSegments(index.indexId()).stream().map(Segment::segmentId).toList());
      if (!segmentIds.contains(segmentId)) {
        segmentIds.add(segmentId);
      }
      manifestVersions.append(
          new ManifestVersion(
              index.indexId(), nextVersion, JSON.writeValueAsString(segmentIds), clock.instant()));
    } catch (JsonProcessingException | RuntimeException e) {
      failed(segmentId, PHASE_CATALOG, e);
      throw new CommitFailedException(PHASE_CATALOG, e);
    }

    try {
      persistStagedTombstones(index.indexId());
    } catch (Exception e) {
      // Post-manifest: the new segment is already ACTIVE and the manifest
      // has advanced, so we do not roll it back. Staged deletes remain in
      // staging (removeAll only runs on success inside persistStagedTombstones)
      // and are retried on the next commit. The failures counter is still
      // incremented so alerting can catch sustained tombstone persistence
      // problems.
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "tombstone persistence failed for index {} after successful commit {}; staged deletes preserved for retry",
            index.indexId(),
            segmentId,
            e);
      }
      Counter.builder("vectorstore.commit.failures")
          .description("Count of commit failures, tagged by phase")
          .tag("phase", PHASE_TOMBSTONES)
          .register(meterRegistry)
          .increment();
    }

    return new CommitOutcome(
        segmentId, built.vectorCount(), built.bytes(), nextVersion, clock.instant());
  }

  /**
   * Resolve each staged user ID against every active segment's ordinal
   * map and union the matching ordinals into that segment's persisted
   * {@code tombstones.roar}. Existing tombstone content is preserved
   * (merged with the new ordinals) so prior deletes remain in force. Only
   * segments that gained new tombstones are re-uploaded; untouched
   * sidecars are left alone.
   *
   * <p>Staging is cleared only for the IDs that made it into this commit;
   * a concurrent delete arriving mid-commit stays for the next one.
   */
  private void persistStagedTombstones(String indexId) throws IOException {
    Set<String> staged = tombstones.snapshot(indexId);
    if (staged.isEmpty()) {
      return;
    }
    List<Segment> activeSegments = manifestResolver.activeSegments(indexId);
    for (Segment segment : activeSegments) {
      RoaringBitmap additions = searcher.ordinalsOf(segment, staged);
      if (additions.isEmpty()) {
        continue;
      }
      TombstoneSidecar existing;
      try (InputStream in = segmentStore.openSidecar(segment, "tombstones.roar")) {
        existing = TombstoneSidecar.read(in);
      }
      TombstoneSidecar merged = existing.mergedWith(additions);
      segmentStore.putSidecar(segment, "tombstones.roar", merged.toBytes());
      // Drop the stale sidecar entry so the next query re-reads the fresh
      // bytes. Keeping the merged object out of the cache is fine — the
      // cache will repopulate on first use.
      sidecarLoader.invalidate(segment);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "persisted {} new tombstones into segment {} (total now {})",
            additions.getCardinality(),
            segment.segmentId(),
            merged.bitmap().getCardinality());
      }
    }
    tombstones.removeAll(indexId, staged);
  }

  private static String objectPrefixFor(VectorIndex index, String segmentId) {
    String unqualified =
        index.indexId().startsWith(index.bucketId() + "/")
            ? index.indexId().substring(index.bucketId().length() + 1)
            : index.indexId();
    return index.bucketId() + "/" + unqualified + "/" + segmentId;
  }

  private void failed(String segmentId, String phase, Throwable cause) {
    try {
      segments.updateState(segmentId, SegmentState.RETIRED);
    } catch (RuntimeException ignore) {
      // Best-effort — do not swallow the original failure with a catalog error.
    }
    Counter.builder("vectorstore.commit.failures")
        .description("Count of commit failures, tagged by phase")
        .tag("phase", phase)
        .register(meterRegistry)
        .increment();
  }
}
