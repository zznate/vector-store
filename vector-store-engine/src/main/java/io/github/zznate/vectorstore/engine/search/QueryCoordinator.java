package io.github.zznate.vectorstore.engine.search;

import io.github.zznate.vectorstore.core.catalog.manifest.ManifestResolver;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.engine.tombstone.InMemoryTombstones;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the active manifest for an index, fans out a single-segment
 * search over each active segment, and merges the per-segment hits into a
 * top-k by score.
 *
 * <p>The fan-out-and-merge pattern is intentional even when the active
 * segment list has size 1 — it is a Phase 1 invariant per
 * {@code docs/design-notes.md} and makes the code path agnostic to segment
 * count as the project grows into compaction and concurrent writers.
 *
 * <p>Tombstones are per-index and in-memory in phase 2; the coordinator
 * asks the {@link Searcher} to build an accept mask that excludes the
 * tombstoned user IDs before calling {@link Searcher#search}.
 */
@ApplicationScoped
public class QueryCoordinator {

  private final ManifestResolver manifests;
  private final Searcher searcher;
  private final InMemoryTombstones tombstones;
  private final Tracer tracer;
  private final MeterRegistry meterRegistry;

  @Inject
  public QueryCoordinator(
      ManifestResolver manifests,
      Searcher searcher,
      InMemoryTombstones tombstones,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.manifests = manifests;
    this.searcher = searcher;
    this.tombstones = tombstones;
    this.tracer = tracer;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Run a kNN query against {@code indexId}, returning the top-{@code topK}
   * hits by score across every active segment. Returns an empty list if
   * the index has no committed segments yet (an index with zero commits
   * is a valid state, not an error).
   */
  public List<ScoredOrdinal> query(String indexId, float[] queryVector, int topK) {
    Span span =
        tracer.spanBuilder("vectorstore.query.fanout").setAttribute("index_id", indexId).startSpan();
    long started = System.nanoTime();
    try (Scope ignored = span.makeCurrent()) {
      List<Segment> active = manifests.activeSegments(indexId);
      if (active.isEmpty()) {
        return List.of();
      }
      Set<String> denied = tombstones.tombstonedIds(indexId);
      PriorityQueue<ScoredOrdinal> topHeap =
          new PriorityQueue<>(topK, Comparator.comparing(ScoredOrdinal::score));
      for (Segment segment : active) {
        var acceptMask = searcher.buildAcceptMask(segment, denied);
        List<ScoredOrdinal> perSegment =
            searcher.search(segment, queryVector, topK, acceptMask);
        for (ScoredOrdinal hit : perSegment) {
          if (topHeap.size() < topK) {
            topHeap.offer(hit);
          } else if (hit.score() > topHeap.peek().score()) {
            topHeap.poll();
            topHeap.offer(hit);
          }
        }
      }
      List<ScoredOrdinal> merged = new ArrayList<>(topHeap);
      merged.sort(Comparator.comparing(ScoredOrdinal::score).reversed());
      return merged;
    } finally {
      Timer.builder("vectorstore.query.duration")
          .description("Wall time of a query fan-out + merge")
          .tag("index_id", indexId)
          .register(meterRegistry)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      span.end();
    }
  }
}
