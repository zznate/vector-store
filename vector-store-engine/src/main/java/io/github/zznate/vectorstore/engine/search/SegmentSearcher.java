package io.github.zznate.vectorstore.engine.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.util.FixedBitSet;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Searcher} implementation that opens a segment's on-disk graph via
 * {@link SegmentStore#openGraph}, runs a JVector {@link GraphSearcher},
 * and translates graph ordinals to user IDs via the per-segment
 * {@code ordinals.jsonl} sidecar.
 *
 * <p>The ordinal map is cached in-process per segment (keyed by
 * {@code segment_id}). Each segment's map is small enough that a plain
 * array keeps the translation O(1) and cache-friendly. Entries are
 * loaded lazily on first access.
 */
@ApplicationScoped
public class SegmentSearcher implements Searcher {

  private static final VectorTypeSupport VTS =
      VectorizationProvider.getInstance().getVectorTypeSupport();

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SegmentStore segmentStore;
  private final VectorIndexRepository indexes;
  private final Tracer tracer;
  private final MeterRegistry meterRegistry;

  private final ConcurrentHashMap<String, String[]> ordinalMaps = new ConcurrentHashMap<>();

  @Inject
  public SegmentSearcher(
      SegmentStore segmentStore,
      VectorIndexRepository indexes,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.segmentStore = segmentStore;
    this.indexes = indexes;
    this.tracer = tracer;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public List<ScoredOrdinal> search(Segment segment, float[] queryVector, int topK, Bits accept) {
    String[] ordinalMap = ordinalMap(segment);
    VectorSimilarityFunction similarity = jvectorSimilarity(segment);
    VectorFloat<?> query = VTS.createFloatVector(queryVector);

    Span span =
        tracer
            .spanBuilder("vectorstore.query.segment.search")
            .setAttribute("segment_id", segment.segmentId())
            .setAttribute("index_id", segment.indexId())
            .startSpan();
    try (Scope ignored = span.makeCurrent();
        RandomAccessReader reader = segmentStore.openGraph(segment);
        OnDiskGraphIndex onDiskGraph = OnDiskGraphIndex.load(() -> reader);
        OnDiskGraphIndex.View view = onDiskGraph.getView()) {
      SearchResult result = GraphSearcher.search(query, topK, view, similarity, onDiskGraph, accept);

      DistributionSummary.builder("vectorstore.query.nodes_visited")
          .description("Graph nodes visited during a query")
          .baseUnit("nodes")
          .tag("index_id", segment.indexId())
          .register(meterRegistry)
          .record(result.getVisitedCount());

      SearchResult.NodeScore[] nodes = result.getNodes();
      List<ScoredOrdinal> hits = new ArrayList<>(nodes.length);
      for (SearchResult.NodeScore node : nodes) {
        if (node.node < ordinalMap.length) {
          hits.add(new ScoredOrdinal(node.node, ordinalMap[node.node], node.score));
        }
      }
      return hits;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      span.end();
    }
  }

  @Override
  public boolean contains(Segment segment, String userId) {
    String[] ordinalMap = ordinalMap(segment);
    for (String candidate : ordinalMap) {
      if (userId.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Bits buildAcceptMask(Segment segment, Set<String> deniedUserIds) {
    if (deniedUserIds == null || deniedUserIds.isEmpty()) {
      return Bits.ALL;
    }
    String[] ordinalMap = ordinalMap(segment);
    FixedBitSet bits = new FixedBitSet(ordinalMap.length);
    for (int ordinal = 0; ordinal < ordinalMap.length; ordinal++) {
      if (!deniedUserIds.contains(ordinalMap[ordinal])) {
        bits.set(ordinal);
      }
    }
    return bits;
  }

  private String[] ordinalMap(Segment segment) {
    return ordinalMaps.computeIfAbsent(segment.segmentId(), id -> loadOrdinalMap(segment));
  }

  private String[] loadOrdinalMap(Segment segment) {
    try (var in = segmentStore.openSidecar(segment, "ordinals.jsonl");
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
      Map<Integer, String> byOrdinal = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        OrdinalLine parsed = JSON.readValue(line, OrdinalLine.class);
        byOrdinal.put(parsed.ordinal, parsed.userId);
      }
      int size = byOrdinal.isEmpty() ? 0 : byOrdinal.keySet().stream().mapToInt(Integer::intValue).max().getAsInt() + 1;
      String[] map = new String[size];
      byOrdinal.forEach((ordinal, userId) -> map[ordinal] = userId);
      return map;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load ordinals.jsonl for segment " + segment.segmentId(), e);
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

  private record OrdinalLine(int ordinal, String userId) {}
}
