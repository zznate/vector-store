package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-lived per-segment query state. Bundles the loaded JVector graph
 * index, the parsed ordinal-to-user-id map, and a pool of reusable
 * {@link io.github.jbellis.jvector.graph.GraphSearcher} instances so a
 * cached handle can serve every query against that segment without
 * re-opening the graph file, re-parsing {@code ordinals.jsonl}, or
 * reallocating the searcher's scratch heaps.
 *
 * <p>The underlying {@link OnDiskGraphIndex} is thread-safe for
 * concurrent {@code getView()} calls; the {@link GraphSearcherPool}
 * coordinates per-thread checkout of searchers (each searcher owns one
 * View, so the pool also caps the number of open per-segment readers).
 */
public record SegmentHandle(
    Segment segment, OnDiskGraphIndex graph, String[] ordinalMap, GraphSearcherPool searcherPool)
    implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SegmentHandle.class);

  /**
   * Release every per-segment resource. Called by the cache when the
   * handle is evicted; callers must not invoke directly on a cached
   * handle — that races any in-flight query against this segment.
   * Closes the searcher pool first (closing each pooled searcher's
   * View and underlying reader), then the graph itself.
   */
  @Override
  public void close() {
    searcherPool.close();
    try {
      graph.close();
    } catch (Exception e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("failed to close OnDiskGraphIndex for segment {}", segment.segmentId(), e);
      }
    }
  }
}
