package io.github.zznate.vectorstore.metadata.filter;

import io.github.zznate.vectorstore.metadata.sidecar.OrdinalAttributes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.roaringbitmap.RoaringBitmap;

/**
 * Compiles a {@link FilterExpr} against one segment's {@link
 * OrdinalAttributes} view into a {@link RoaringBitsAdapter}. Phase 1 is a
 * brute-force scan: every ordinal is evaluated; matches are recorded in a
 * {@link RoaringBitmap}. Phase 2 will pre-compute per-attribute posting
 * lists at segment build time and replace the scan with bitmap
 * intersections — this type's public contract is stable.
 *
 * <p>Every compile is wrapped in a {@code vectorstore.filter.compile} span
 * and its duration recorded on {@code vectorstore.filter.compile.duration},
 * tagged by {@code index_id}, {@code term_count}, and the match-rate
 * bucket {@code result_ratio_bucket}.
 */
@ApplicationScoped
public class FilterCompiler {

  private static final String SPAN_COMPILE = "vectorstore.filter.compile";
  private static final String METER_COMPILE_DURATION = "vectorstore.filter.compile.duration";

  private static final String TAG_INDEX_ID = "index_id";
  private static final String TAG_TERM_COUNT = "term_count";
  private static final String TAG_RESULT_RATIO_BUCKET = "result_ratio_bucket";

  private final MeterRegistry meterRegistry;
  private final Tracer tracer;

  @Inject
  public FilterCompiler(MeterRegistry meterRegistry, Tracer tracer) {
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
  }

  /**
   * Compile {@code filter} against {@code attributes} for the segment
   * identified by {@code indexId} / {@code segmentId}. A {@code null}
   * filter is treated as "accept all" and short-circuits to a bitmap that
   * contains every ordinal.
   */
  public RoaringBitsAdapter compile(
      FilterExpr filter, OrdinalAttributes attributes, String indexId, String segmentId) {
    int size = attributes.size();
    if (filter == null) {
      RoaringBitmap all = new RoaringBitmap();
      if (size > 0) {
        all.add(0L, (long) size);
      }
      return new RoaringBitsAdapter(all, size);
    }

    Span span =
        tracer
            .spanBuilder(SPAN_COMPILE)
            .setAttribute("segment_id", segmentId)
            .setAttribute("index_id", indexId)
            .setAttribute("term_count", (long) filter.termCount())
            .setAttribute("vector_count", (long) size)
            .startSpan();
    long startNanos = System.nanoTime();
    RoaringBitmap bitmap = new RoaringBitmap();
    int accepted = 0;
    try (Scope ignored = span.makeCurrent()) {
      for (int ordinal = 0; ordinal < size; ordinal++) {
        if (evaluate(filter, attributes.attributesOf(ordinal))) {
          bitmap.add(ordinal);
          accepted++;
        }
      }
      span.setAttribute("accepted_count", (long) accepted);
    } finally {
      long elapsed = System.nanoTime() - startNanos;
      Timer.builder(METER_COMPILE_DURATION)
          .tag(TAG_INDEX_ID, indexId)
          .tag(TAG_TERM_COUNT, Integer.toString(filter.termCount()))
          .tag(TAG_RESULT_RATIO_BUCKET, bucketFor(size, accepted))
          .register(meterRegistry)
          .record(elapsed, TimeUnit.NANOSECONDS);
      span.end();
    }
    return new RoaringBitsAdapter(bitmap, size);
  }

  private static boolean evaluate(FilterExpr expr, Map<String, String> attrs) {
    return switch (expr) {
      case FilterExpr.Equals eq -> eq.value().equals(attrs.get(eq.key()));
      case FilterExpr.And and -> {
        for (FilterExpr term : and.terms()) {
          if (!evaluate(term, attrs)) {
            yield false;
          }
        }
        yield true;
      }
    };
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
