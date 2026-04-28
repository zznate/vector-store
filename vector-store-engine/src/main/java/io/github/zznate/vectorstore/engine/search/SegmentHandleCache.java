package io.github.zznate.vectorstore.engine.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.zznate.vectorstore.core.cache.CacheConfig;
import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bounded per-segment {@link SegmentHandle} cache. Each handle is loaded
 * on cold access, reused by every subsequent query, and closed on LRU
 * eviction or explicit unpin.
 *
 * <p>Concurrent cold access for the same segment single-flights through a
 * per-id lock so only one loader runs. The load span
 * {@code vectorstore.cache.segment_handle.load} records the cost on the
 * cold path.
 *
 * <p>Two storage layers coexist:
 *
 * <ol>
 *   <li>An LRU {@link HeapCacheTier} bounded by entry count, the default
 *       store for SMART-policy indexes.
 *   <li>A {@link #pin pinned} map keyed by segment id that is exempt from
 *       LRU eviction. {@link io.github.zznate.vectorstore.core.cache.CachePolicy#RESIDENT}
 *       indexes pin every active segment via this path so warm-tier
 *       latency never pays a re-load.
 * </ol>
 *
 * <p>{@link #get} consults the pinned map first, falling through to the
 * LRU tier. Pinning a segment also evicts any duplicate copy from the LRU
 * tier so a process never holds two {@link OnDiskGraphIndex} instances for
 * the same segment.
 *
 * <p>Known race: an LRU eviction fires while another thread is reading
 * through the handle's {@link OnDiskGraphIndex}. The evicted handle's
 * {@code close()} may release resources the reader still touches. In
 * practice evictions are rare when the cache is sized to the working set;
 * reference-counted retention would eliminate the race entirely if it
 * matters in production. Pinned handles are not subject to this race
 * because they are not LRU-evicted.
 */
@ApplicationScoped
public class SegmentHandleCache {

  public static final String CACHE_NAME = "segment_handle";
  public static final int DEFAULT_MAX_ENTRIES = 256;
  public static final String SPAN_LOAD = "vectorstore.cache.segment_handle.load";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SegmentStore segmentStore;
  private final Tracer tracer;
  private final HeapCacheTier<String, SegmentHandle> tier;

  // Per-segment lock objects for single-flight loads. Entries are kept for
  // the segment's lifetime in the cache and only removed by
  // {@link #invalidate} / {@link #invalidateAll}; we deliberately do not
  // remove per-call. The classic "computeIfAbsent + synchronized +
  // finally remove" idiom has a window where two threads can end up
  // synchronized on different lock instances for the same id, breaking
  // mutual exclusion. Per-segment Objects are tiny and the map is bounded
  // by segment count.
  private final ConcurrentMap<String, Object> loadLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, SegmentHandle> pinned = new ConcurrentHashMap<>();

  @Inject
  public SegmentHandleCache(
      SegmentStore segmentStore,
      Tracer tracer,
      MeterRegistry meterRegistry,
      CacheConfig config) {
    this(segmentStore, tracer, meterRegistry, config.segmentHandle().maxEntries());
  }

  public SegmentHandleCache(
      SegmentStore segmentStore, Tracer tracer, MeterRegistry meterRegistry, int maxEntries) {
    this.segmentStore = segmentStore;
    this.tracer = tracer;
    this.tier =
        HeapCacheTier.<String, SegmentHandle>builder(CACHE_NAME)
            .countWeighted(maxEntries)
            .meterRegistry(meterRegistry)
            .onRemoval((id, handle) -> handle.close())
            .build();
  }

  /**
   * Returns a cached or freshly loaded handle for {@code segment}. Pinned
   * handles take precedence; on miss the LRU tier is consulted, then the
   * single-flight loader runs.
   */
  public SegmentHandle get(Segment segment) throws IOException {
    String id = segment.segmentId();
    SegmentHandle pinnedHandle = pinned.get(id);
    if (pinnedHandle != null) {
      return pinnedHandle;
    }
    Optional<SegmentHandle> cached = tier.get(id);
    if (cached.isPresent()) {
      return cached.get();
    }
    Object lock = loadLocks.computeIfAbsent(id, k -> new Object());
    synchronized (lock) {
      cached = tier.get(id);
      if (cached.isPresent()) {
        return cached.get();
      }
      SegmentHandle loaded = loadUnderSpan(segment);
      tier.put(id, loaded);
      return loaded;
    }
  }

  /**
   * Pin a segment's handle so it is never LRU-evicted while pinned.
   * Idempotent: re-pinning returns the existing pinned handle. Drops any
   * duplicate copy from the LRU tier so the process holds at most one
   * graph index per segment.
   *
   * <p>Used by the cache-policy enforcer to keep
   * {@link io.github.zznate.vectorstore.core.cache.CachePolicy#RESIDENT}
   * indexes resident across query bursts and SMART-index eviction
   * pressure.
   */
  public SegmentHandle pin(Segment segment) throws IOException {
    String id = segment.segmentId();
    SegmentHandle existing = pinned.get(id);
    if (existing != null) {
      return existing;
    }
    Object lock = loadLocks.computeIfAbsent(id, k -> new Object());
    synchronized (lock) {
      existing = pinned.get(id);
      if (existing != null) {
        return existing;
      }
      // Drop any tier copy first so we never hold two OnDiskGraphIndex
      // instances open against the same segment.
      tier.invalidate(id);
      SegmentHandle loaded = loadUnderSpan(segment);
      pinned.put(id, loaded);
      return loaded;
    }
  }

  /**
   * Release a pinned handle. The handle is closed synchronously. No-op if
   * {@code segmentId} was not pinned.
   */
  public void unpin(String segmentId) {
    SegmentHandle removed = pinned.remove(segmentId);
    if (removed != null) {
      removed.close();
    }
  }

  /** Whether {@code segmentId} is currently pinned. */
  public boolean isPinned(String segmentId) {
    return pinned.containsKey(segmentId);
  }

  /** Number of currently-pinned handles. */
  public int pinnedCount() {
    return pinned.size();
  }

  /** Drop the cached handle for {@code segmentId}, closing it. Does not unpin. */
  public void invalidate(String segmentId) {
    tier.invalidate(segmentId);
    loadLocks.remove(segmentId);
  }

  /** Drop every cached handle, closing each one. Also unpins everything. */
  public void invalidateAll() {
    for (SegmentHandle handle : pinned.values()) {
      handle.close();
    }
    pinned.clear();
    tier.invalidateAll();
    loadLocks.clear();
  }

  /** Underlying tier for stats reporting and test assertions. */
  public HeapCacheTier<String, SegmentHandle> tier() {
    return tier;
  }

  private SegmentHandle loadUnderSpan(Segment segment) throws IOException {
    Span span =
        tracer
            .spanBuilder(SPAN_LOAD)
            .setAttribute("segment_id", segment.segmentId())
            .setAttribute("index_id", segment.indexId())
            .startSpan();
    try {
      ReaderSupplier supplier = segmentStore.openGraph(segment);
      OnDiskGraphIndex graph = OnDiskGraphIndex.load(supplier);
      String[] ordinalMap = loadOrdinalMap(segment);
      return new SegmentHandle(segment, graph, ordinalMap);
    } finally {
      span.end();
    }
  }

  private String[] loadOrdinalMap(Segment segment) throws IOException {
    try (InputStream in = segmentStore.openSidecar(segment, "ordinals.jsonl");
        BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
      Map<Integer, String> byOrdinal = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        OrdinalLine parsed = JSON.readValue(line, OrdinalLine.class);
        byOrdinal.put(parsed.ordinal, parsed.userId);
      }
      int size =
          byOrdinal.isEmpty()
              ? 0
              : byOrdinal.keySet().stream().mapToInt(Integer::intValue).max().getAsInt() + 1;
      String[] map = new String[size];
      byOrdinal.forEach((ordinal, userId) -> map[ordinal] = userId);
      return map;
    }
  }

  private record OrdinalLine(int ordinal, String userId) {}
}
