package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-lived per-segment query state. Bundles the loaded JVector graph
 * index and the parsed ordinal-to-user-id map so a cached handle can
 * serve every query against that segment without re-opening the graph
 * file or re-parsing {@code ordinals.jsonl}. The underlying
 * {@link OnDiskGraphIndex} is thread-safe for concurrent
 * {@code getView()} calls; views themselves are short-lived per-query
 * state and remain the caller's responsibility to close.
 */
public record SegmentHandle(Segment segment, OnDiskGraphIndex graph, String[] ordinalMap)
    implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SegmentHandle.class);

  /**
   * Release the underlying graph resources. Called by the cache when the
   * handle is evicted; callers must not invoke directly on a cached
   * handle — that races any in-flight query against this segment.
   */
  @Override
  public void close() {
    try {
      graph.close();
    } catch (Exception e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("failed to close OnDiskGraphIndex for segment {}", segment.segmentId(), e);
      }
    }
  }
}
