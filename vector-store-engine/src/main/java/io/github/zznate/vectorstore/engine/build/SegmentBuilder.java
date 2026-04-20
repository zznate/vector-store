package io.github.zznate.vectorstore.engine.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.buffer.BufferSnapshot;
import io.github.zznate.vectorstore.metadata.sidecar.AttributeSidecarWriter;
import io.github.zznate.vectorstore.metadata.sidecar.TombstoneSidecar;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Builds a segment on local disk from a {@link BufferSnapshot}.
 *
 * <p>Produces, under a fresh temp directory, the files every vector-store
 * segment carries:
 *
 * <ul>
 *   <li>{@code graph.jvec} — the JVector graph index written with the
 *       {@code InlineVectors} feature.
 *   <li>{@code ordinals.jsonl} — one {@code {"ordinal":N,"userId":"..."}}
 *       per line, same order as the JVector ordinals.
 *   <li>{@code header.json} — segment-level metadata (see
 *       {@link SegmentHeader}).
 *   <li>{@code attributes.jsonl} — one
 *       {@code {"ordinal":N,"attributes":{...}}} per line, populated from
 *       the {@link BufferEntry#attributes()} supplied at ingest.
 *   <li>{@code tombstones.roar} — serialised empty {@link
 *       org.roaringbitmap.RoaringBitmap}; the commit coordinator merges
 *       staged deletes into this sidecar (and every prior active
 *       segment's sidecar) after publish.
 * </ul>
 *
 * <p>Wrapped in OTel spans {@code vectorstore.commit.build} (graph
 * construction) and {@code vectorstore.commit.serialize} (file writes).
 * Emits the {@code vectorstore.commit.duration} timer tagged by
 * {@code phase=build|serialize} and the
 * {@code vectorstore.commit.segment_bytes} distribution summary.
 *
 * <p>Product quantisation is deliberately skipped in this phase — segments
 * use the {@code InlineVectors} feature and recall is served by the raw
 * vectors carried inline. Phase 3 or 4 can layer {@code FusedPQ} on the
 * same segment layout by adding a {@code pq.bin} sidecar next to
 * {@code graph.jvec} and switching the writer feature set.
 */
@ApplicationScoped
public class SegmentBuilder {

  private static final VectorTypeSupport VTS =
      VectorizationProvider.getInstance().getVectorTypeSupport();

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** Phase of the commit pipeline each span / metric is tagged with. */
  public static final String PHASE_BUILD = "build";

  public static final String PHASE_SERIALIZE = "serialize";

  private final Clock clock;
  private final Tracer tracer;
  private final MeterRegistry meterRegistry;

  @Inject
  public SegmentBuilder(Clock clock, Tracer tracer, MeterRegistry meterRegistry) {
    this.clock = clock;
    this.tracer = tracer;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Build a segment from {@code snapshot} with the given {@code segmentId}.
   * Every entry must have the same dimension as {@code dimension}; the
   * caller (the commit path) is expected to have validated that at ingress.
   *
   * <p>The commit coordinator generates the segment ID up-front so it can
   * create a {@code BUILDING} catalog row for audit before the build
   * starts; the builder uses whatever ID is supplied.
   *
   * @throws IllegalArgumentException if the snapshot is empty
   * @throws IOException on file I/O failure
   */
  public BuiltSegment build(
      String segmentId,
      BufferSnapshot snapshot,
      int dimension,
      DistanceMetric metric,
      IndexBuildParams params)
      throws IOException {
    if (snapshot.isEmpty()) {
      throw new IllegalArgumentException(
          "cannot build segment from empty buffer for index " + snapshot.indexId());
    }

    Path tempDir = Files.createTempDirectory("vs-segment-");
    Instant startedAt = clock.instant();

    List<VectorFloat<?>> jvectorValues = toJVectorValues(snapshot.entries(), dimension);
    RandomAccessVectorValues ravv = new ListRandomAccessVectorValues(jvectorValues, dimension);

    buildAndWrite(snapshot, segmentId, tempDir, ravv, metric, params);

    Instant finishedAt = clock.instant();
    long bytes = sizeOf(tempDir);
    DistributionSummary.builder("vectorstore.commit.segment_bytes")
        .description("Bytes of the segment produced by a commit")
        .baseUnit("bytes")
        .register(meterRegistry)
        .record(bytes);

    return new BuiltSegment(
        segmentId, tempDir, snapshot.size(), bytes, params, finishedAt.isAfter(startedAt) ? finishedAt : startedAt);
  }

  private void buildAndWrite(
      BufferSnapshot snapshot,
      String segmentId,
      Path tempDir,
      RandomAccessVectorValues ravv,
      DistanceMetric metric,
      IndexBuildParams params)
      throws IOException {
    Span buildSpan = tracer.spanBuilder("vectorstore.commit.build").startSpan();
    io.github.jbellis.jvector.graph.ImmutableGraphIndex graph;
    long buildStart = System.nanoTime();
    try (Scope ignored = buildSpan.makeCurrent();
        GraphIndexBuilder builder =
            new GraphIndexBuilder(
                ravv,
                toJVector(metric),
                params.m(),
                params.beamWidth(),
                params.neighborOverflow(),
                params.alpha(),
                params.addHierarchy())) {
      graph = builder.build(ravv);
    } finally {
      buildSpan.end();
      Timer.builder("vectorstore.commit.duration")
          .description("Wall time of a commit, tagged by phase")
          .tag("phase", PHASE_BUILD)
          .register(meterRegistry)
          .record(System.nanoTime() - buildStart, TimeUnit.NANOSECONDS);
    }

    Span serializeSpan = tracer.spanBuilder("vectorstore.commit.serialize").startSpan();
    long serializeStart = System.nanoTime();
    try (Scope ignored = serializeSpan.makeCurrent()) {
      OnDiskGraphIndex.write(graph, ravv, tempDir.resolve("graph.jvec"));
      writeOrdinals(snapshot.entries(), tempDir.resolve("ordinals.jsonl"));
      writeHeader(
          tempDir.resolve("header.json"),
          new SegmentHeader(
              SegmentHeader.CURRENT_SCHEMA_VERSION,
              segmentId,
              snapshot.size(),
              ravv.dimension(),
              metric,
              params,
              clock.instant()));
      List<java.util.Map<String, String>> byOrdinal = new ArrayList<>(snapshot.size());
      for (BufferEntry entry : snapshot.entries()) {
        byOrdinal.add(entry.attributes() == null ? java.util.Map.of() : entry.attributes());
      }
      AttributeSidecarWriter.write(tempDir.resolve("attributes.jsonl"), byOrdinal);
      // Empty tombstone bitmap at segment-build time; CommitCoordinator's
      // tombstone pass overwrites this sidecar when staged deletes need to
      // be persisted against this (or any earlier active) segment.
      Files.write(tempDir.resolve("tombstones.roar"), TombstoneSidecar.empty().toBytes());
    } finally {
      serializeSpan.end();
      Timer.builder("vectorstore.commit.duration")
          .tag("phase", PHASE_SERIALIZE)
          .register(meterRegistry)
          .record(System.nanoTime() - serializeStart, TimeUnit.NANOSECONDS);
    }
  }

  private static List<VectorFloat<?>> toJVectorValues(List<BufferEntry> entries, int dimension) {
    List<VectorFloat<?>> out = new ArrayList<>(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      BufferEntry entry = entries.get(i);
      if (entry.vector().length != dimension) {
        throw new IllegalArgumentException(
            "entry %d (userId=%s) has dimension %d, expected %d"
                .formatted(i, entry.userId(), entry.vector().length, dimension));
      }
      out.add(VTS.createFloatVector(entry.vector()));
    }
    return out;
  }

  private static VectorSimilarityFunction toJVector(DistanceMetric metric) {
    return switch (metric) {
      case COSINE -> VectorSimilarityFunction.COSINE;
      case EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
      case DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
    };
  }

  private static void writeOrdinals(List<BufferEntry> entries, Path path) throws IOException {
    try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
        BufferEntry entry = entries.get(ordinal);
        w.write(
            JSON.writeValueAsString(new OrdinalLine(ordinal, entry.userId())));
        w.newLine();
      }
    }
  }

  private static void writeHeader(Path path, SegmentHeader header) throws IOException {
    try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      JSON.writerWithDefaultPrettyPrinter().writeValue(w, header);
    }
  }

  private static long sizeOf(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      long total = 0;
      for (Path p : stream.toList()) {
        total += Files.size(p);
      }
      return total;
    }
  }

  private record OrdinalLine(int ordinal, String userId) {}
}
