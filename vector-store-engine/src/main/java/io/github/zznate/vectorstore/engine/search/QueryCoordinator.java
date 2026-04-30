package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.util.Bits;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.engine.tombstone.CatalogStagedTombstones;
import io.github.zznate.vectorstore.metadata.filter.FilterCompiler;
import io.github.zznate.vectorstore.metadata.filter.FilterExpr;
import io.github.zznate.vectorstore.metadata.filter.RoaringBitsAdapter;
import io.github.zznate.vectorstore.metadata.posting.PostingListReader;
import io.github.zznate.vectorstore.metadata.sidecar.AttributeSidecar;
import io.github.zznate.vectorstore.metadata.sidecar.SidecarLoader;
import io.github.zznate.vectorstore.metadata.sidecar.TombstoneSidecar;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.roaringbitmap.RoaringBitmap;

/**
 * Resolves the active manifest for an index, fans out a single-segment
 * search over each active segment, and merges the per-segment hits into a
 * top-k by score. Compiles the optional {@link FilterExpr} into a
 * {@link Bits} accept mask per segment, combined with both persisted
 * tombstones (from {@code tombstones.roar}) and in-memory staged deletes.
 *
 * <p>The fan-out-and-merge pattern is intentional even when the active
 * segment list has size 1 — it is a Phase 1 invariant per
 * {@code docs/design-notes.md} and keeps the code path agnostic to segment
 * count as the project grows into compaction and concurrent writers.
 *
 * <p>Per-segment accept mask =
 * {@code filterBits AND NOT (persistedTombstones OR stagedOrdinals)}.
 * {@code filterBits} defaults to "accept all" when no filter is supplied;
 * if there are also no tombstones (either kind) the coordinator
 * short-circuits to {@link Bits#ALL} so the common no-filter / no-delete
 * path allocates nothing extra.
 */
@ApplicationScoped
public class QueryCoordinator {

  private final ManifestCache manifests;
  private final Searcher searcher;
  private final CachePolicyEnforcer cachePolicyEnforcer;
  private final CatalogStagedTombstones tombstones;
  private final SidecarLoader sidecarLoader;
  private final FilterCompiler filterCompiler;
  private final Tracer tracer;
  private final MeterRegistry meterRegistry;

  @Inject
  public QueryCoordinator(
      ManifestCache manifests,
      Searcher searcher,
      CachePolicyEnforcer cachePolicyEnforcer,
      CatalogStagedTombstones tombstones,
      SidecarLoader sidecarLoader,
      FilterCompiler filterCompiler,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.manifests = manifests;
    this.searcher = searcher;
    this.cachePolicyEnforcer = cachePolicyEnforcer;
    this.tombstones = tombstones;
    this.sidecarLoader = sidecarLoader;
    this.filterCompiler = filterCompiler;
    this.tracer = tracer;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Run a kNN query against {@code indexId}, returning the top-{@code topK}
   * hits by score across every active segment. {@code filter} is optional
   * (pass {@code null} for "no filter"); {@code tuning} carries the
   * JVector per-query knobs (use {@link SearchTuning#defaults(int)} when
   * the caller has no preference). Returns an empty list if the index has
   * no committed segments yet.
   */
  public List<ScoredHit> query(
      String indexId,
      float[] queryVector,
      int topK,
      FilterExpr filter,
      SearchTuning tuning) {
    Span span =
        tracer.spanBuilder("vectorstore.query.fanout").setAttribute("index_id", indexId).startSpan();
    long started = System.nanoTime();
    try (Scope ignored = span.makeCurrent()) {
      List<Segment> active = manifests.activeSegments(indexId);
      if (active.isEmpty()) {
        return List.of();
      }
      cachePolicyEnforcer.onQuery(indexId, active);
      Set<String> stagedDenied = tombstones.tombstonedIds(indexId);
      List<Pending> topResults =
          fanOutAndMerge(active, queryVector, topK, filter, tuning, stagedDenied, indexId);
      return enrichWithAttributes(topResults);
    } finally {
      Timer.builder("vectorstore.query.duration")
          .description("Wall time of a query fan-out + merge")
          .tag("index_id", indexId)
          .register(meterRegistry)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      span.end();
    }
  }

  /**
   * Search every active segment under the index's accept mask and merge
   * the per-segment hits into a top-{@code topK} list ordered by score
   * (descending). Package-private so the merge logic can be exercised in
   * isolation.
   */
  List<Pending> fanOutAndMerge(
      List<Segment> active,
      float[] queryVector,
      int topK,
      FilterExpr filter,
      SearchTuning tuning,
      Set<String> stagedDenied,
      String indexId) {
    PriorityQueue<Pending> topHeap =
        new PriorityQueue<>(topK, Comparator.comparing(p -> p.hit.score()));
    for (Segment segment : active) {
      Bits accept = acceptFor(segment, indexId, filter, stagedDenied);
      if (accept == null) {
        // Segment has zero vectors under this filter — skip the search
        // entirely.
        continue;
      }
      List<ScoredOrdinal> perSegment =
          searcher.search(segment, queryVector, topK, accept, tuning);
      offerInto(topHeap, segment, perSegment, topK);
    }
    List<Pending> merged = new ArrayList<>(topHeap);
    merged.sort(Comparator.comparing((Pending p) -> p.hit.score()).reversed());
    return merged;
  }

  /**
   * Push every per-segment hit through a bounded min-heap of size
   * {@code topK}. Once the heap is full, only hits whose score beats the
   * current minimum are admitted, so the heap converges to the global
   * top-k across every segment.
   */
  private static void offerInto(
      PriorityQueue<Pending> topHeap, Segment segment, List<ScoredOrdinal> perSegment, int topK) {
    for (ScoredOrdinal hit : perSegment) {
      Pending pending = new Pending(segment, hit);
      if (topHeap.size() < topK) {
        topHeap.offer(pending);
      } else if (hit.score() > topHeap.peek().hit.score()) {
        topHeap.poll();
        topHeap.offer(pending);
      }
    }
  }

  /**
   * Resolve attribute sidecars for the segments that produced the merged
   * top-k and return user-facing {@link ScoredHit} records. Sidecars are
   * cached locally so repeated hits from the same segment pay one lookup.
   * Package-private so the enrichment can be exercised in isolation.
   */
  List<ScoredHit> enrichWithAttributes(List<Pending> merged) {
    List<ScoredHit> enriched = new ArrayList<>(merged.size());
    Map<String, AttributeSidecar> attrCache = new HashMap<>();
    for (Pending p : merged) {
      Map<String, String> attrs = attributesFor(p, attrCache);
      enriched.add(new ScoredHit(p.hit.userId(), p.hit.score(), attrs));
    }
    return enriched;
  }

  private Map<String, String> attributesFor(Pending p, Map<String, AttributeSidecar> attrCache) {
    if (p.hit.ordinal() < 0) {
      return Map.of();
    }
    AttributeSidecar sidecar =
        attrCache.computeIfAbsent(
            p.segment.segmentId(), id -> sidecarLoader.attributes(p.segment));
    return p.hit.ordinal() < sidecar.size() ? sidecar.attributesOf(p.hit.ordinal()) : Map.of();
  }

  /**
   * Build the combined accept mask for {@code segment} or return
   * {@link Bits#ALL} if both the filter and tombstones are trivial.
   * Returns {@code null} when the filter rejects every ordinal in the
   * segment so the caller can skip the JVector search entirely.
   */
  private Bits acceptFor(
      Segment segment, String indexId, FilterExpr filter, Set<String> stagedDenied) {
    int size = Math.toIntExact(segment.vectorCount());

    TombstoneSidecar persistedTombstones = sidecarLoader.tombstones(segment);
    RoaringBitmap stagedOrdinals =
        stagedDenied.isEmpty() ? new RoaringBitmap() : searcher.ordinalsOf(segment, stagedDenied);

    boolean hasDeny =
        !persistedTombstones.isEmpty() || !stagedOrdinals.isEmpty();
    if (filter == null && !hasDeny) {
      recordFilteredRatio(indexId, 1.0);
      return Bits.ALL;
    }

    RoaringBitmap accept;
    if (filter == null) {
      accept = new RoaringBitmap();
      if (size > 0) {
        accept.add(0L, (long) size);
      }
    } else {
      AttributeSidecar sidecar = sidecarLoader.attributes(segment);
      PostingListReader postings = sidecarLoader.postings(segment);
      accept =
          filterCompiler
              .compile(filter, sidecar, postings, indexId, segment.segmentId())
              .bitmap()
              .clone();
    }
    if (hasDeny) {
      RoaringBitmap deny = persistedTombstones.bitmap().clone();
      deny.or(stagedOrdinals);
      accept.andNot(deny);
    }

    double ratio = size == 0 ? 0.0 : (double) accept.getCardinality() / (double) size;
    recordFilteredRatio(indexId, ratio);
    if (accept.isEmpty()) {
      return null;
    }
    return new RoaringBitsAdapter(accept, size);
  }

  private void recordFilteredRatio(String indexId, double ratio) {
    DistributionSummary.builder("vectorstore.query.filtered_ratio")
        .description("Fraction of ordinals accepted per query, per segment")
        .tag("index_id", indexId)
        .scale(100.0)
        .register(meterRegistry)
        .record(ratio);
  }

  private record Pending(Segment segment, ScoredOrdinal hit) {}
}
