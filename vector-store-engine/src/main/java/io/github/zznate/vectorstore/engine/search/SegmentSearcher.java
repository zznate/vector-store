package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Searcher} implementation that opens a segment's on-disk graph
 * via a {@link SegmentHandleCache}-cached {@link SegmentHandle}, runs a
 * pooled {@link GraphSearcher} from the handle's
 * {@link GraphSearcherPool}, and translates graph ordinals to user IDs
 * via the handle's ordinal map.
 *
 * <p>The query loop matches the upstream JVector convention:
 * <ol>
 *   <li>Acquire a searcher from the per-segment pool. The searcher
 *       owns its own {@code View} (and therefore one underlying
 *       reader); the pool reuses both across queries.
 *   <li>Derive the {@link RandomAccessVectorValues} for scoring from
 *       {@code searcher.getView()} so the searcher's existing reader
 *       is reused — no second view is opened.
 *   <li>Run {@link GraphSearcher#search} with an exact
 *       {@link DefaultSearchScoreProvider} (rerankK = topK is correct
 *       for the InlineVectors-only feature set; a future PQ adoption
 *       will widen the rerank pool).
 *   <li>Release the searcher to the pool on success; close it on
 *       failure to avoid pooling state with partially mutated scratch
 *       heaps.
 * </ol>
 */
@ApplicationScoped
public class SegmentSearcher implements Searcher {

  private static final Logger LOG = LoggerFactory.getLogger(SegmentSearcher.class);

  private static final VectorTypeSupport VTS =
      VectorizationProvider.getInstance().getVectorTypeSupport();

  private final SegmentHandleCache handles;
  private final VectorIndexRepository indexes;
  private final Tracer tracer;
  private final MeterRegistry meterRegistry;

  @Inject
  public SegmentSearcher(
      SegmentHandleCache handles,
      VectorIndexRepository indexes,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.handles = handles;
    this.indexes = indexes;
    this.tracer = tracer;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public List<ScoredOrdinal> search(
      Segment segment, float[] queryVector, int topK, Bits accept, SearchTuning tuning) {
    SegmentHandle handle = handleFor(segment);
    VectorSimilarityFunction similarity = jvectorSimilarity(segment);
    VectorFloat<?> query = VTS.createFloatVector(queryVector);

    Span span =
        tracer
            .spanBuilder("vectorstore.query.segment.search")
            .setAttribute("segment_id", segment.segmentId())
            .setAttribute("index_id", segment.indexId())
            .startSpan();

    GraphSearcher searcher = handle.searcherPool().acquire();
    boolean handed = false;
    try (Scope ignored = span.makeCurrent()) {
      RandomAccessVectorValues ravv = (RandomAccessVectorValues) searcher.getView();
      SearchScoreProvider ssp = DefaultSearchScoreProvider.exact(query, similarity, ravv);
      SearchResult result =
          searcher.search(
              ssp, topK, tuning.rerankK(), tuning.threshold(), tuning.rerankFloor(), accept);

      DistributionSummary.builder("vectorstore.query.nodes_visited")
          .description("Graph nodes visited during a query")
          .baseUnit("nodes")
          .tag("index_id", segment.indexId())
          .register(meterRegistry)
          .record(result.getVisitedCount());

      List<ScoredOrdinal> hits = mapHits(result, handle.ordinalMap());
      handle.searcherPool().release(searcher);
      handed = true;
      return hits;
    } finally {
      if (!handed) {
        try {
          searcher.close();
        } catch (Exception closeErr) {
          if (LOG.isWarnEnabled()) {
            LOG.warn(
                "failed to close discarded GraphSearcher for segment {}",
                segment.segmentId(),
                closeErr);
          }
        }
      }
      span.end();
    }
  }

  @Override
  public int findOrdinal(Segment segment, String userId) {
    String[] ordinalMap = handleFor(segment).ordinalMap();
    for (int i = 0; i < ordinalMap.length; i++) {
      if (userId.equals(ordinalMap[i])) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public org.roaringbitmap.RoaringBitmap ordinalsOf(Segment segment, Set<String> userIds) {
    org.roaringbitmap.RoaringBitmap bitmap = new org.roaringbitmap.RoaringBitmap();
    if (userIds == null || userIds.isEmpty()) {
      return bitmap;
    }
    String[] ordinalMap = handleFor(segment).ordinalMap();
    for (int ordinal = 0; ordinal < ordinalMap.length; ordinal++) {
      if (userIds.contains(ordinalMap[ordinal])) {
        bitmap.add(ordinal);
      }
    }
    return bitmap;
  }

  private static List<ScoredOrdinal> mapHits(SearchResult result, String[] ordinalMap) {
    SearchResult.NodeScore[] nodes = result.getNodes();
    List<ScoredOrdinal> hits = new ArrayList<>(nodes.length);
    for (SearchResult.NodeScore node : nodes) {
      if (node.node < ordinalMap.length) {
        hits.add(new ScoredOrdinal(node.node, ordinalMap[node.node], node.score));
      }
    }
    return hits;
  }

  private SegmentHandle handleFor(Segment segment) {
    try {
      return handles.get(segment);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load handle for segment " + segment.segmentId(), e);
    }
  }

  private VectorSimilarityFunction jvectorSimilarity(Segment segment) {
    DistanceMetric metric =
        indexes
            .findById(segment.indexId())
            .map(VectorIndex::metric)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "segment " + segment.segmentId() + " references unknown index"));
    return switch (metric) {
      case COSINE -> VectorSimilarityFunction.COSINE;
      case EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
      case DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
    };
  }
}
