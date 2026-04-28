package io.github.zznate.vectorstore.metadata.filter;

import io.github.zznate.vectorstore.metadata.posting.PostingListReader;
import io.github.zznate.vectorstore.metadata.sidecar.OrdinalAttributes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.roaringbitmap.RoaringBitmap;

/**
 * Compiles a {@link FilterExpr} against one segment's per-ordinal
 * attribute view (and, when available, its {@link PostingListReader})
 * into a {@link RoaringBitsAdapter}.
 *
 * <p>Two strategies, selected by a small rule-based planner:
 *
 * <ul>
 *   <li><b>Posting list</b> — applies when every leaf predicate
 *       ({@link FilterExpr.Equals}, {@link FilterExpr.In}) names a key
 *       that has a posting list in {@link PostingListReader#indexedKeys()}.
 *       Each leaf resolves to a {@link RoaringBitmap}; combinators are
 *       computed as {@code AND} / {@code OR} / {@code andNot} bitmap ops.
 *       {@link FilterExpr.Not} subtracts from the full ordinal range.
 *   <li><b>Scan</b> — fallback for any case the posting-list strategy
 *       cannot resolve (no postings sidecar, or any leaf key not
 *       indexed). Brute-force ordinal scan against the attribute view.
 * </ul>
 *
 * <p>Selection is intentionally rule-based today; cost-based selection
 * (using per-key cardinality + selectivity hints) is future work.
 *
 * <p>Every compile is wrapped in a {@code vectorstore.filter.compile}
 * span and its duration recorded on
 * {@code vectorstore.filter.compile.duration}, tagged by
 * {@code index_id}, {@code term_count}, {@code result_ratio_bucket}, and
 * {@code strategy}. The strategy choice itself is counted on
 * {@code vectorstore.filter.strategy{strategy=posting_list|scan}}.
 */
@ApplicationScoped
public class FilterCompiler {

  private static final String SPAN_COMPILE = "vectorstore.filter.compile";
  private static final String METER_COMPILE_DURATION = "vectorstore.filter.compile.duration";
  private static final String METER_FILTER_STRATEGY = "vectorstore.filter.strategy";

  private static final String TAG_INDEX_ID = "index_id";
  private static final String TAG_TERM_COUNT = "term_count";
  private static final String TAG_RESULT_RATIO_BUCKET = "result_ratio_bucket";
  private static final String TAG_STRATEGY = "strategy";

  static final String STRATEGY_POSTING_LIST = "posting_list";
  static final String STRATEGY_SCAN = "scan";

  private final MeterRegistry meterRegistry;
  private final Tracer tracer;

  @Inject
  public FilterCompiler(MeterRegistry meterRegistry, Tracer tracer) {
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
  }

  /**
   * Compile {@code filter} into an accept bitmap for the segment
   * identified by {@code indexId} / {@code segmentId}. A {@code null}
   * filter is treated as "accept all" and short-circuits to a full
   * bitmap. {@code postings} may be {@code null}, which forces the
   * scan strategy (used by tests, and by any caller that has not yet
   * loaded the posting-list sidecar).
   */
  public RoaringBitsAdapter compile(
      FilterExpr filter,
      OrdinalAttributes attributes,
      PostingListReader postings,
      String indexId,
      String segmentId) {
    int size = attributes.size();
    if (filter == null) {
      return new RoaringBitsAdapter(fullBitmap(size), size);
    }

    String strategy = chooseStrategy(filter, postings);
    incrementStrategyCounter(strategy, indexId);

    Span span = startCompileSpan(indexId, segmentId, filter, size, strategy);
    long startNanos = System.nanoTime();
    RoaringBitmap bitmap;
    int accepted = 0;
    try (Scope ignored = span.makeCurrent()) {
      bitmap =
          STRATEGY_POSTING_LIST.equals(strategy)
              ? compileViaPostings(filter, postings, size)
              : compileViaScan(filter, attributes);
      accepted = bitmap.getCardinality();
      span.setAttribute("accepted_count", (long) accepted);
    } finally {
      recordDuration(filter, indexId, strategy, size, accepted, System.nanoTime() - startNanos);
      span.end();
    }
    return new RoaringBitsAdapter(bitmap, size);
  }

  // ---- strategy selection ----

  private static String chooseStrategy(FilterExpr filter, PostingListReader postings) {
    if (postings == null) {
      return STRATEGY_SCAN;
    }
    return canResolveAll(filter, postings.indexedKeys()) ? STRATEGY_POSTING_LIST : STRATEGY_SCAN;
  }

  private static boolean canResolveAll(FilterExpr expr, Set<String> indexedKeys) {
    return switch (expr) {
      case FilterExpr.Equals eq -> indexedKeys.contains(eq.key());
      case FilterExpr.In in -> indexedKeys.contains(in.key());
      case FilterExpr.And and -> allResolve(and.terms(), indexedKeys);
      case FilterExpr.Or or -> allResolve(or.terms(), indexedKeys);
      case FilterExpr.Not not -> canResolveAll(not.term(), indexedKeys);
    };
  }

  private static boolean allResolve(java.util.List<FilterExpr> terms, Set<String> indexedKeys) {
    for (FilterExpr term : terms) {
      if (!canResolveAll(term, indexedKeys)) {
        return false;
      }
    }
    return true;
  }

  // ---- posting-list strategy ----

  private static RoaringBitmap compileViaPostings(
      FilterExpr expr, PostingListReader postings, int size) {
    return switch (expr) {
      case FilterExpr.Equals eq -> postings.lookup(eq.key(), eq.value()).orElseGet(RoaringBitmap::new);
      case FilterExpr.In in -> compileInViaPostings(in, postings);
      case FilterExpr.And and -> compileAndViaPostings(and.terms(), postings, size);
      case FilterExpr.Or or -> compileOrViaPostings(or.terms(), postings, size);
      case FilterExpr.Not not -> compileNotViaPostings(not.term(), postings, size);
    };
  }

  private static RoaringBitmap compileInViaPostings(
      FilterExpr.In in, PostingListReader postings) {
    RoaringBitmap result = new RoaringBitmap();
    for (String value : in.values()) {
      postings.lookup(in.key(), value).ifPresent(result::or);
    }
    return result;
  }

  private static RoaringBitmap compileAndViaPostings(
      java.util.List<FilterExpr> terms, PostingListReader postings, int size) {
    RoaringBitmap result = compileViaPostings(terms.get(0), postings, size);
    for (int i = 1; i < terms.size(); i++) {
      result.and(compileViaPostings(terms.get(i), postings, size));
    }
    return result;
  }

  private static RoaringBitmap compileOrViaPostings(
      java.util.List<FilterExpr> terms, PostingListReader postings, int size) {
    RoaringBitmap result = new RoaringBitmap();
    for (FilterExpr term : terms) {
      result.or(compileViaPostings(term, postings, size));
    }
    return result;
  }

  private static RoaringBitmap compileNotViaPostings(
      FilterExpr inner, PostingListReader postings, int size) {
    RoaringBitmap inverted = compileViaPostings(inner, postings, size);
    RoaringBitmap all = fullBitmap(size);
    all.andNot(inverted);
    return all;
  }

  // ---- scan strategy ----

  private static RoaringBitmap compileViaScan(FilterExpr filter, OrdinalAttributes attributes) {
    int size = attributes.size();
    RoaringBitmap bitmap = new RoaringBitmap();
    for (int ordinal = 0; ordinal < size; ordinal++) {
      if (evaluate(filter, attributes.attributesOf(ordinal))) {
        bitmap.add(ordinal);
      }
    }
    return bitmap;
  }

  private static boolean evaluate(FilterExpr expr, Map<String, String> attrs) {
    return switch (expr) {
      case FilterExpr.Equals eq -> eq.value().equals(attrs.get(eq.key()));
      case FilterExpr.In in -> {
        String value = attrs.get(in.key());
        yield value != null && in.values().contains(value);
      }
      case FilterExpr.And and -> {
        for (FilterExpr term : and.terms()) {
          if (!evaluate(term, attrs)) {
            yield false;
          }
        }
        yield true;
      }
      case FilterExpr.Or or -> {
        for (FilterExpr term : or.terms()) {
          if (evaluate(term, attrs)) {
            yield true;
          }
        }
        yield false;
      }
      case FilterExpr.Not not -> !evaluate(not.term(), attrs);
    };
  }

  // ---- shared helpers ----

  private static RoaringBitmap fullBitmap(int size) {
    RoaringBitmap bitmap = new RoaringBitmap();
    if (size > 0) {
      bitmap.add(0L, (long) size);
    }
    return bitmap;
  }

  // ---- observability ----

  private Span startCompileSpan(
      String indexId, String segmentId, FilterExpr filter, int size, String strategy) {
    return tracer
        .spanBuilder(SPAN_COMPILE)
        .setAttribute("segment_id", segmentId)
        .setAttribute("index_id", indexId)
        .setAttribute("term_count", (long) filter.termCount())
        .setAttribute("vector_count", (long) size)
        .setAttribute(TAG_STRATEGY, strategy)
        .startSpan();
  }

  private void incrementStrategyCounter(String strategy, String indexId) {
    Counter.builder(METER_FILTER_STRATEGY)
        .description("Filter compile strategy chosen by the planner, per segment")
        .tag(TAG_STRATEGY, strategy)
        .tag(TAG_INDEX_ID, indexId)
        .register(meterRegistry)
        .increment();
  }

  private void recordDuration(
      FilterExpr filter,
      String indexId,
      String strategy,
      int size,
      int accepted,
      long elapsedNanos) {
    Timer.builder(METER_COMPILE_DURATION)
        .tag(TAG_INDEX_ID, indexId)
        .tag(TAG_TERM_COUNT, Integer.toString(filter.termCount()))
        .tag(TAG_RESULT_RATIO_BUCKET, bucketFor(size, accepted))
        .tag(TAG_STRATEGY, strategy)
        .register(meterRegistry)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Map the match rate {@code accepted / size} to one of the four fixed
   * histogram buckets from the design notes. Using string tags keeps the
   * Prometheus cardinality small and predictable. When {@code size == 0}
   * the bucket is reported as the empty-corpus sentinel {@code "0-25"}
   * (trivially "under 25%").
   */
  static String bucketFor(int size, int accepted) {
    if (size == 0) {
      return "0-25";
    }
    double ratio = (double) accepted / (double) size;
    if (ratio < 0.25) {
      return "0-25";
    }
    if (ratio < 0.50) {
      return "25-50";
    }
    if (ratio < 0.75) {
      return "50-75";
    }
    return "75-100";
  }
}
