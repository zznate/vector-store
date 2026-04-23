package io.github.zznate.vectorstore.engine.tombstone;

import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-index staging set of tombstoned user IDs backed by the
 * {@code staged_tombstone} catalog table. Delete requests land here and
 * survive process restart; {@link
 * io.github.zznate.vectorstore.engine.commit.CommitCoordinator} drains the
 * set into each active segment's {@code tombstones.roar} sidecar on the next
 * commit and atomically clears the rows via
 * {@code CatalogCommitPublisher}.
 *
 * <p>Queries read the snapshot alongside persisted bitmaps so a delete is
 * visible immediately, before the next commit.
 */
@ApplicationScoped
public class CatalogStagedTombstones {

  static final String METER_STAGED = "vectorstore.tombstone.staged";
  static final String METER_UNSTAGED = "vectorstore.tombstone.unstaged";
  static final String METER_STAGED_COUNT = "vectorstore.tombstone.staged.count";
  static final String TAG_INDEX = "index_id";

  private final StagedTombstoneRepository repository;
  private final Clock clock;
  private final MeterRegistry meterRegistry;
  private final ConcurrentHashMap<String, Boolean> gaugeRegistered = new ConcurrentHashMap<>();

  @Inject
  public CatalogStagedTombstones(
      StagedTombstoneRepository repository, Clock clock, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  /** Mark every given {@code userId} as deleted for {@code indexId}. */
  public void tombstone(String indexId, Collection<String> userIds) {
    if (userIds.isEmpty()) {
      return;
    }
    repository.stage(indexId, userIds, clock.instant());
    ensureGauge(indexId);
    Counter.builder(METER_STAGED)
        .description("User IDs enqueued for tombstoning since process start")
        .tag(TAG_INDEX, indexId)
        .register(meterRegistry)
        .increment(userIds.size());
  }

  /** Snapshot of the current staged IDs for {@code indexId}. */
  public Set<String> tombstonedIds(String indexId) {
    return repository.snapshot(indexId);
  }

  /** True if {@code userId} is currently staged for {@code indexId}. */
  public boolean isTombstoned(String indexId, String userId) {
    return repository.isStaged(indexId, userId);
  }

  /**
   * Snapshot the staged set for {@code indexId} without mutating it. The
   * commit coordinator persists the snapshot, then atomically clears it via
   * {@code CatalogCommitPublisher} once the manifest-version append
   * succeeds. If the surrounding commit fails, the staging rows are
   * untouched and the next commit retries the same IDs.
   */
  public Set<String> snapshot(String indexId) {
    return repository.snapshot(indexId);
  }

  /**
   * Remove the given user IDs from the staged set for {@code indexId}. This
   * method is a non-transactional fallback; the commit path should unstage
   * inside the manifest-append transaction via {@code
   * CatalogCommitPublisher}. Tests and compensating flows may still call
   * here directly.
   */
  public void removeAll(String indexId, Set<String> userIds) {
    if (userIds.isEmpty()) {
      return;
    }
    repository.unstage(indexId, userIds);
    Counter.builder(METER_UNSTAGED)
        .description("User IDs cleared from staging after successful commit")
        .tag(TAG_INDEX, indexId)
        .register(meterRegistry)
        .increment(userIds.size());
  }

  /**
   * Emit the unstaged counter without touching the repository. Called by
   * the commit path after {@code CatalogCommitPublisher} has already cleared
   * the rows in the surrounding transaction.
   */
  public void recordUnstaged(String indexId, int count) {
    if (count <= 0) {
      return;
    }
    Counter.builder(METER_UNSTAGED)
        .description("User IDs cleared from staging after successful commit")
        .tag(TAG_INDEX, indexId)
        .register(meterRegistry)
        .increment(count);
  }

  private void ensureGauge(String indexId) {
    gaugeRegistered.computeIfAbsent(
        indexId,
        id -> {
          Gauge.builder(METER_STAGED_COUNT, repository, r -> r.count(id))
              .description("Staged tombstones pending commit for this index")
              .tag(TAG_INDEX, id)
              .strongReference(true)
              .register(meterRegistry);
          return Boolean.TRUE;
        });
  }
}
