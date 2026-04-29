package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded pool of {@link GraphSearcher} instances over a single
 * {@link OnDiskGraphIndex}. The upstream JVector convention is one
 * searcher per query thread (not per query): the searcher owns
 * reusable scratch heaps that are expensive to allocate, and its
 * constructor opens a fresh reader from the {@code ReaderSupplier} on
 * every call. Pooling amortises both costs across queries.
 *
 * <p>Soft cap: idle searchers above {@code maxIdle} are closed on
 * release rather than queued. The cap controls the maximum number of
 * open per-segment views (and therefore underlying readers) at rest.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #acquire} returns a queued or freshly constructed
 *       searcher; callers MUST release in a try/finally.
 *   <li>{@link #release} returns a searcher to the queue, or closes it
 *       if the pool is full or already closed.
 *   <li>{@link #close} drains the idle queue and disables future
 *       pooling. In-flight searchers are unaffected; on their release
 *       they are closed instead of queued.
 * </ul>
 *
 * <p>Searchers exposed by a panicking query path should be discarded
 * (closed directly) rather than released, to avoid pooling state with
 * partially mutated scratch heaps.
 */
public final class GraphSearcherPool implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(GraphSearcherPool.class);

  /** Default soft cap on idle searchers per segment. */
  public static final int DEFAULT_MAX_IDLE = 4;

  private final OnDiskGraphIndex graph;
  private final int maxIdle;
  private final Queue<GraphSearcher> idle = new ConcurrentLinkedQueue<>();
  private final AtomicInteger idleSize = new AtomicInteger();
  private volatile boolean closed;

  public GraphSearcherPool(OnDiskGraphIndex graph) {
    this(graph, DEFAULT_MAX_IDLE);
  }

  public GraphSearcherPool(OnDiskGraphIndex graph, int maxIdle) {
    if (maxIdle < 0) {
      throw new IllegalArgumentException("maxIdle must be non-negative, got " + maxIdle);
    }
    this.graph = graph;
    this.maxIdle = maxIdle;
  }

  /**
   * Acquire a searcher. Returns a queued instance when one is available
   * or constructs a fresh one. Pairs with {@link #release}.
   */
  public GraphSearcher acquire() {
    GraphSearcher pooled = idle.poll();
    if (pooled != null) {
      idleSize.decrementAndGet();
      return pooled;
    }
    return new GraphSearcher(graph);
  }

  /**
   * Return {@code searcher} to the pool. Closes it instead when the
   * pool is full or already closed.
   */
  public void release(GraphSearcher searcher) {
    if (closed || idleSize.get() >= maxIdle) {
      closeQuietly(searcher);
      return;
    }
    idle.offer(searcher);
    idleSize.incrementAndGet();
  }

  @Override
  public void close() {
    closed = true;
    GraphSearcher s;
    while ((s = idle.poll()) != null) {
      idleSize.decrementAndGet();
      closeQuietly(s);
    }
  }

  /** Number of searchers currently queued; tests + observability only. */
  public int idleCount() {
    return idleSize.get();
  }

  private static void closeQuietly(GraphSearcher s) {
    try {
      s.close();
    } catch (Exception e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("failed to close GraphSearcher", e);
      }
    }
  }
}
