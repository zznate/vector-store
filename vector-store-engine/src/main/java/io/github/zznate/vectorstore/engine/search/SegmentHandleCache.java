package io.github.zznate.vectorstore.engine.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
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
 * Bounded per-segment {@link SegmentHandle} cache. Replaces the unbounded
 * {@code ordinalMaps} map that {@code SegmentSearcher} used to keep: now
 * every handle has a clear lifecycle — loaded on cold access, reused by
 * every subsequent query, closed on LRU eviction.
 *
 * <p>Concurrent cold access for the same segment single-flights through a
 * per-id lock so only one loader runs. The load span
 * {@code vectorstore.cache.segment_handle.load} records the cost on the
 * cold path.
 *
 * <p>Known race: an LRU eviction fires while another thread is reading
 * through the handle's {@link OnDiskGraphIndex}. The evicted handle's
 * {@code close()} may release resources the reader still touches. In
 * practice evictions are rare when the cache is sized to the working set;
 * a follow-up iteration can add reference-counted retention to eliminate
 * the race entirely.
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
  private final ConcurrentMap<String, Object> loadLocks = new ConcurrentHashMap<>();

  @Inject
  public SegmentHandleCache(
      SegmentStore segmentStore, Tracer tracer, MeterRegistry meterRegistry) {
    this(segmentStore, tracer, meterRegistry, DEFAULT_MAX_ENTRIES);
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

  /** Returns a cached or freshly loaded handle for {@code segment}. */
  public SegmentHandle get(Segment segment) throws IOException {
    String id = segment.segmentId();
    Optional<SegmentHandle> cached = tier.get(id);
    if (cached.isPresent()) {
      return cached.get();
    }
    Object lock = loadLocks.computeIfAbsent(id, k -> new Object());
    try {
      synchronized (lock) {
        cached = tier.get(id);
        if (cached.isPresent()) {
          return cached.get();
        }
        SegmentHandle loaded = loadUnderSpan(segment);
        tier.put(id, loaded);
        return loaded;
      }
    } finally {
      loadLocks.remove(id, lock);
    }
  }

  /** Drop the cached handle for {@code segmentId}, closing it. */
  public void invalidate(String segmentId) {
    tier.invalidate(segmentId);
  }

  /** Drop every cached handle, closing each one. */
  public void invalidateAll() {
    tier.invalidateAll();
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
