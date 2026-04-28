package io.github.zznate.vectorstore.engine.commit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zznate.vectorstore.core.catalog.jdbi.CatalogCommitPublisher;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentIds;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.engine.buffer.BufferSnapshot;
import io.github.zznate.vectorstore.engine.buffer.WriteBuffer;
import io.github.zznate.vectorstore.engine.build.SegmentBuilder;
import io.github.zznate.vectorstore.engine.search.Searcher;
import io.github.zznate.vectorstore.engine.tombstone.CatalogStagedTombstones;
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
 * catalog row → build on disk → publish via {@link SegmentStore} → stage
 * tombstone sidecars on S3 → atomically flip segment to ACTIVE, append
 * manifest_version, and clear staged tombstone rows.
 *
 * <p>The final triple (segment state transition, manifest append, staged
 * unstage) runs in one JDBI transaction via {@link CatalogCommitPublisher}
 * so staging and manifest are never out of agreement. Tombstone sidecar
 * writes happen first and are idempotent (bitmap union): a transaction
 * failure re-runs the sidecar writes on retry without creating duplicates.
 *
 * <p>Every failure path retires the segment row (state = {@code RETIRED})
 * rather than deleting it, so the catalog retains an audit trail, and
 * increments the {@code vectorstore.commit.failures} counter tagged by the
 * phase the failure occurred in: {@code build}, {@code publish},
 * {@code tombstones}, or {@code catalog}.
 *
 * <p>Concurrent commits for the same index are serialised by a per-index
 * {@link ReentrantLock}. Commits for different indexes never contend.
 */
@ApplicationScoped
public class CommitCoordinator {

  /** Counter tag values for {@code vectorstore.commit.failures}. */
  public static final String PHASE_BUILD = "build";

  public static final String PHASE_PUBLISH = "publish";
  public static final String PHASE_TOMBSTONES = "tombstones";
  public static final String PHASE_CATALOG = "catalog";

  private static final Logger LOG = LoggerFactory.getLogger(CommitCoordinator.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final WriteBuffer writeBuffer;
  private final SegmentBuilder builder;
  private final SegmentStore segmentStore;
  private final SegmentRepository segments;
  private final ManifestCache manifests;
  private final CatalogCommitPublisher commitPublisher;
  private final CatalogStagedTombstones tombstones;
  private final Searcher searcher;
  private final SidecarLoader sidecarLoader;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  private final ConcurrentHashMap<String, ReentrantLock> perIndexLocks = new ConcurrentHashMap<>();

  // Eleven CDI-injected collaborators. PMD's ExcessiveParameterList rule is
  // a code-smell detector for over-broad methods; @Inject constructors are
  // a different shape — the framework dictates the surface and bundling
  // deps into a parameter object would hide the count without adding any
  // real abstraction. Each argument is a distinct, well-named bean.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  @Inject
  public CommitCoordinator(
      WriteBuffer writeBuffer,
      SegmentBuilder builder,
      SegmentStore segmentStore,
      SegmentRepository segments,
      ManifestCache manifests,
      CatalogCommitPublisher commitPublisher,
      CatalogStagedTombstones tombstones,
      Searcher searcher,
      SidecarLoader sidecarLoader,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.writeBuffer = writeBuffer;
    this.builder = builder;
    this.segmentStore = segmentStore;
    this.segments = segments;
    this.manifests = manifests;
    this.commitPublisher = commitPublisher;
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
   * @throws CommitFailedException if any build, publish, tombstone, or
   *     catalog step fails. The segment row (if created) is left in
   *     {@code RETIRED} state and the {@code vectorstore.commit.failures}
   *     counter is incremented with the phase tag.
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

  /**
   * Drop the per-index commit lock for {@code indexId}. Called when an
   * index is deleted so the {@code perIndexLocks} map does not grow
   * monotonically with every index ever created. No-op if the index has
   * never been committed.
   *
   * <p>Safe to call concurrently with a commit only when the index has
   * already been deleted from the catalog (no future commit can reach
   * this entry); otherwise a concurrent commit would re-create the
   * mapping immediately, defeating the cleanup.
   */
  public void invalidateIndex(String indexId) {
    perIndexLocks.remove(indexId);
  }

  private CommitOutcome commitLocked(VectorIndex index) throws CommitFailedException {
    BufferSnapshot snapshot = writeBuffer.snapshotAndClear(index.indexId());
    if (snapshot.isEmpty()) {
      throw new EmptyCommitException(index.indexId());
    }

    String segmentId = SegmentIds.newSegmentId();
    String objectPrefix = objectPrefixFor(index, segmentId);

    BuiltSegment built = buildAndPublishSegment(index, snapshot, segmentId, objectPrefix);

    Set<String> staged = tombstones.snapshot(index.indexId());
    List<Segment> willBeActive = computeWillBeActive(index.indexId(), segmentId, objectPrefix, built);

    applyStagedTombstonesAcross(willBeActive, staged, segmentId);

    return finalizePublish(index, segmentId, built, willBeActive, staged);
  }

  /**
   * Create the BUILDING catalog row, build the graph on disk, and publish
   * the segment artefacts to the object store. Each phase's failure is
   * tagged on {@code vectorstore.commit.failures} and the segment row is
   * retired in {@link #failed}. Package-private so individual phases can
   * be exercised directly from tests.
   */
  BuiltSegment buildAndPublishSegment(
      VectorIndex index, BufferSnapshot snapshot, String segmentId, String objectPrefix)
      throws CommitFailedException {
    IndexBuildParams params = IndexBuildParams.fromJson(index.engineParams());
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
    return built;
  }

  /**
   * Compute the full list of segments that will be active after this
   * commit: every previously-ACTIVE segment plus a synthetic
   * {@link Segment} record for the just-built one (already on the object
   * store but still BUILDING in the catalog).
   */
  List<Segment> computeWillBeActive(
      String indexId, String newSegmentId, String objectPrefix, BuiltSegment built) {
    List<Segment> previousActive = manifests.activeSegments(indexId);
    Segment newSegment =
        new Segment(
            newSegmentId,
            indexId,
            SegmentState.ACTIVE,
            built.vectorCount(),
            built.bytes(),
            objectPrefix,
            clock.instant());
    List<Segment> willBeActive = new ArrayList<>(previousActive.size() + 1);
    willBeActive.addAll(previousActive);
    willBeActive.add(newSegment);
    return willBeActive;
  }

  /**
   * Apply the staged tombstones to every segment that will be active
   * after the commit. Sidecar updates are idempotent bitmap unions, so a
   * subsequent transactional failure is safe to retry. A failure here is
   * tagged as {@code phase=tombstones}; {@code failureSegmentId} is the
   * just-built segment whose row is retired by {@link #failed}.
   */
  void applyStagedTombstonesAcross(
      List<Segment> willBeActive, Set<String> staged, String failureSegmentId)
      throws CommitFailedException {
    if (staged.isEmpty()) {
      return;
    }
    try {
      for (Segment segment : willBeActive) {
        applyStagedTombstones(segment, staged);
      }
    } catch (IOException e) {
      failed(failureSegmentId, PHASE_TOMBSTONES, e);
      throw new CommitFailedException(PHASE_TOMBSTONES, e);
    }
  }

  /**
   * Atomic catalog step: flip the new segment to ACTIVE, append the
   * manifest version, and clear the staged tombstone rows — all in one
   * JDBI transaction via {@link io.github.zznate.vectorstore.core.catalog.jdbi.CatalogCommitPublisher}.
   * Also records the unstaged counter and warms the manifest cache with
   * the new version.
   */
  CommitOutcome finalizePublish(
      VectorIndex index,
      String segmentId,
      BuiltSegment built,
      List<Segment> willBeActive,
      Set<String> staged)
      throws CommitFailedException {
    int nextVersion;
    try {
      nextVersion = manifests.currentVersion(index.indexId()).orElse(0) + 1;
      List<String> segmentIds = new ArrayList<>(willBeActive.size());
      for (Segment segment : willBeActive) {
        segmentIds.add(segment.segmentId());
      }
      commitPublisher.publish(
          segmentId,
          SegmentState.ACTIVE,
          built.bytes(),
          new ManifestVersion(
              index.indexId(), nextVersion, JSON.writeValueAsString(segmentIds), clock.instant()),
          index.indexId(),
          staged);
      tombstones.recordUnstaged(index.indexId(), staged.size());
      manifests.populate(index.indexId(), nextVersion, willBeActive);
    } catch (JsonProcessingException | RuntimeException e) {
      failed(segmentId, PHASE_CATALOG, e);
      throw new CommitFailedException(PHASE_CATALOG, e);
    }

    return new CommitOutcome(
        segmentId, built.vectorCount(), built.bytes(), nextVersion, clock.instant());
  }

  /**
   * Resolve each staged user ID against {@code segment}'s ordinal map and
   * union the matching ordinals into the segment's persisted
   * {@code tombstones.roar}. Existing tombstone content is preserved
   * (merged with the new ordinals) so prior deletes remain in force.
   * Segments with no matching ordinals are left untouched.
   */
  private void applyStagedTombstones(Segment segment, Set<String> stagedUserIds) throws IOException {
    RoaringBitmap additions = searcher.ordinalsOf(segment, stagedUserIds);
    if (additions.isEmpty()) {
      return;
    }
    TombstoneSidecar existing;
    try (InputStream in = segmentStore.openSidecar(segment, "tombstones.roar")) {
      existing = TombstoneSidecar.read(in);
    }
    TombstoneSidecar merged = existing.mergedWith(additions);
    segmentStore.putSidecar(segment, "tombstones.roar", merged.toBytes());
    sidecarLoader.invalidate(segment);
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "persisted {} new tombstones into segment {} (total now {})",
          additions.getCardinality(),
          segment.segmentId(),
          merged.bitmap().getCardinality());
    }
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
    } catch (RuntimeException e) {
      // Best-effort — the original failure ({@code cause}) is the signal
      // we want to surface, so we don't rethrow here, but we log the
      // secondary catalog failure with its stack trace so operators can
      // see it.
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "failed to retire segment {} after phase={} failure (original cause logged separately)",
            segmentId,
            phase,
            e);
      }
    }
    Counter.builder("vectorstore.commit.failures")
        .description("Count of commit failures, tagged by phase")
        .tag("phase", phase)
        .register(meterRegistry)
        .increment();
  }
}
