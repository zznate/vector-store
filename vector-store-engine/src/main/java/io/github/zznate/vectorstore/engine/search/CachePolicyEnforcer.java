package io.github.zznate.vectorstore.engine.search;

import io.github.zznate.vectorstore.core.cache.CachePolicy;
import io.github.zznate.vectorstore.core.cache.CachePolicyResolver;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the per-index {@link CachePolicy} to the
 * {@link SegmentHandleCache}.
 *
 * <ul>
 *   <li>{@link CachePolicy#RESIDENT} — every active segment is pinned in
 *       the segment-handle cache before the query fan-out runs. Segments
 *       no longer in the active list are unpinned.
 *   <li>{@link CachePolicy#SMART} (default) — no pinning; the LRU tier
 *       handles eviction.
 *   <li>{@link CachePolicy#MINIMAL} — no pinning here either; the L2
 *       bypass is enforced separately by the storage layer.
 * </ul>
 *
 * <p>{@link #onQuery} is the hot-path entry point and runs once per query.
 * It is idempotent: re-applying with the same active list is cheap and
 * stable.
 *
 * <p>{@link #onSegmentRetired} is invoked when a segment transitions to
 * {@code RETIRED} (e.g. by a future compaction step). It releases any
 * resident pin held against that segment.
 *
 * <p>Per-index gauges {@code vectorstore.cache.resident.bytes} and
 * {@code vectorstore.cache.resident.segments} are registered lazily on
 * first encounter and remain stable for the index's lifetime.
 */
@ApplicationScoped
public class CachePolicyEnforcer {

  public static final String GAUGE_RESIDENT_BYTES = "vectorstore.cache.resident.bytes";
  public static final String GAUGE_RESIDENT_SEGMENTS = "vectorstore.cache.resident.segments";
  public static final String TAG_INDEX_ID = "index_id";

  private static final Logger LOG = LoggerFactory.getLogger(CachePolicyEnforcer.class);

  private final CachePolicyResolver resolver;
  private final SegmentHandleCache handles;
  private final MeterRegistry meterRegistry;

  private final ConcurrentMap<String, Set<String>> residentSegmentsByIndex = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicLong> residentBytesByIndex = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicLong> residentCountByIndex = new ConcurrentHashMap<>();

  @Inject
  public CachePolicyEnforcer(
      CachePolicyResolver resolver, SegmentHandleCache handles, MeterRegistry meterRegistry) {
    this.resolver = resolver;
    this.handles = handles;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Reconcile the segment-handle cache with the index's policy and current
   * active-segment list. For RESIDENT indexes this pins newly active
   * segments and unpins segments no longer active. SMART and MINIMAL
   * indexes are no-ops on this path.
   *
   * <p>Failures are logged with stack trace and swallowed: a pin failure
   * never breaks the query path because the regular {@code get} fallback
   * still loads the handle through the LRU tier.
   */
  public void onQuery(String indexId, List<Segment> activeSegments) {
    CachePolicy policy;
    try {
      policy = resolver.policyFor(indexId);
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("could not resolve cache policy for index {}", indexId, e);
      }
      return;
    }
    if (policy != CachePolicy.RESIDENT) {
      return;
    }
    try {
      reconcileResident(indexId, activeSegments);
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("RESIDENT pinning failed for index {}", indexId, e);
      }
    }
  }

  /** Drop any resident pin held against {@code segmentId}. Idempotent. */
  public void onSegmentRetired(String segmentId) {
    handles.unpin(segmentId);
    residentSegmentsByIndex.values().forEach(set -> set.remove(segmentId));
  }

  /**
   * Forget all resident state across every index. Unpins every resident
   * handle currently held; gauge backings are zeroed but the gauges
   * themselves stay registered.
   */
  public void invalidateAll() {
    for (Set<String> residents : residentSegmentsByIndex.values()) {
      for (String segmentId : residents) {
        handles.unpin(segmentId);
      }
    }
    residentSegmentsByIndex.clear();
    residentBytesByIndex.values().forEach(holder -> holder.set(0L));
    residentCountByIndex.values().forEach(holder -> holder.set(0L));
  }

  /**
   * Forget any resident state for {@code indexId} — used when an index is
   * deleted. Unpins every resident handle and removes the per-index
   * gauges' backing state.
   */
  public void invalidateIndex(String indexId) {
    Set<String> residents = residentSegmentsByIndex.remove(indexId);
    if (residents != null) {
      for (String segmentId : residents) {
        handles.unpin(segmentId);
      }
    }
    AtomicLong bytes = residentBytesByIndex.get(indexId);
    if (bytes != null) {
      bytes.set(0L);
    }
    AtomicLong count = residentCountByIndex.get(indexId);
    if (count != null) {
      count.set(0L);
    }
  }

  /** Visible for testing. Number of segments currently pinned for {@code indexId}. */
  public int residentSegmentCount(String indexId) {
    Set<String> residents = residentSegmentsByIndex.get(indexId);
    return residents == null ? 0 : residents.size();
  }

  private void reconcileResident(String indexId, List<Segment> activeSegments) throws IOException {
    Set<String> residents =
        residentSegmentsByIndex.computeIfAbsent(indexId, k -> ConcurrentHashMap.newKeySet());
    Set<String> activeIds = collectActiveIds(activeSegments);

    pinNewlyActive(indexId, activeSegments, residents);
    unpinNoLongerActive(residents, activeIds);
    refreshGauges(indexId, activeSegments, residents);
  }

  private Set<String> collectActiveIds(List<Segment> activeSegments) {
    Set<String> active = new HashSet<>(activeSegments.size() * 2);
    for (Segment segment : activeSegments) {
      active.add(segment.segmentId());
    }
    return active;
  }

  private void pinNewlyActive(String indexId, List<Segment> active, Set<String> residents)
      throws IOException {
    for (Segment segment : active) {
      if (residents.add(segment.segmentId())) {
        handles.pin(segment);
        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "pinned segment {} for RESIDENT index {}", segment.segmentId(), indexId);
        }
      }
    }
  }

  private void unpinNoLongerActive(Set<String> residents, Set<String> activeIds) {
    Iterator<String> iter = residents.iterator();
    while (iter.hasNext()) {
      String segmentId = iter.next();
      if (!activeIds.contains(segmentId)) {
        handles.unpin(segmentId);
        iter.remove();
      }
    }
  }

  private void refreshGauges(String indexId, List<Segment> active, Set<String> residents) {
    long residentBytes = 0L;
    for (Segment segment : active) {
      if (residents.contains(segment.segmentId())) {
        residentBytes += segment.bytes();
      }
    }
    bytesGaugeFor(indexId).set(residentBytes);
    countGaugeFor(indexId).set(residents.size());
  }

  private AtomicLong bytesGaugeFor(String indexId) {
    return residentBytesByIndex.computeIfAbsent(
        indexId,
        id -> {
          AtomicLong holder = new AtomicLong();
          Gauge.builder(GAUGE_RESIDENT_BYTES, holder, AtomicLong::doubleValue)
              .description("Bytes held resident in the segment-handle cache, per index")
              .baseUnit("bytes")
              .tag(TAG_INDEX_ID, id)
              .register(meterRegistry);
          return holder;
        });
  }

  private AtomicLong countGaugeFor(String indexId) {
    return residentCountByIndex.computeIfAbsent(
        indexId,
        id -> {
          AtomicLong holder = new AtomicLong();
          Gauge.builder(GAUGE_RESIDENT_SEGMENTS, holder, AtomicLong::doubleValue)
              .description("Number of segments held resident in the segment-handle cache, per index")
              .tag(TAG_INDEX_ID, id)
              .register(meterRegistry);
          return holder;
        });
  }
}
