package io.github.zznate.vectorstore.engine.testsupport;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.buffer.BufferSnapshot;
import io.github.zznate.vectorstore.engine.build.SegmentBuilder;
import io.github.zznate.vectorstore.engine.search.SegmentSearcher;
import io.github.zznate.vectorstore.engine.store.LocalSegmentStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wires up {@link SegmentBuilder}, {@link LocalSegmentStore}, and
 * {@link SegmentSearcher} with real instances rooted at a throwaway temp
 * directory — no Quarkus, no CDI, noop OTel, in-memory Micrometer. One
 * place for every engine-level test to reuse.
 */
public final class EngineTestHarness implements AutoCloseable {

  public final Clock clock = Clock.systemUTC();
  public final Tracer tracer = OpenTelemetry.noop().getTracer("vector-store-engine-test");
  public final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  public final Path root;
  public final LocalSegmentStore store;
  public final StubVectorIndexRepository indexes = new StubVectorIndexRepository();
  public final SegmentBuilder builder;
  public final SegmentSearcher searcher;

  private EngineTestHarness(Path root) {
    this.root = root;
    this.store = new LocalSegmentStore(root);
    this.builder = new SegmentBuilder(clock, tracer, meterRegistry);
    this.searcher = new SegmentSearcher(store, indexes, tracer, meterRegistry);
  }

  public static EngineTestHarness create() throws IOException {
    return new EngineTestHarness(Files.createTempDirectory("vs-engine-test-"));
  }

  /**
   * Build a segment from the given entries and publish it under
   * {@code bucket/index/<segmentId>}. The returned {@link Segment} record
   * reflects the BUILDING -> ACTIVE end state with accurate byte size and
   * is ready to pass directly to a {@link SegmentSearcher}.
   */
  public Segment buildAndPublish(
      String bucket,
      String indexName,
      String segmentId,
      List<BufferEntry> entries,
      int dimension,
      DistanceMetric metric,
      IndexBuildParams params)
      throws IOException {
    String indexId = bucket + "/" + indexName;
    indexes.register(
        new VectorIndex(
            indexId,
            bucket,
            indexName,
            dimension,
            metric,
            params.toJson(),
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    BufferSnapshot snapshot = new BufferSnapshot(indexId, entries);
    String objectPrefix = bucket + "/" + indexName + "/" + segmentId;
    BuiltSegment built = builder.build(segmentId, snapshot, dimension, metric, params);
    store.publish(built, objectPrefix);
    return new Segment(
        segmentId,
        indexId,
        SegmentState.ACTIVE,
        built.vectorCount(),
        built.bytes(),
        objectPrefix,
        built.builtAt());
  }

  @Override
  public void close() throws IOException {
    store.close();
    deleteRecursively(root);
  }

  private static void deleteRecursively(Path p) throws IOException {
    if (!Files.exists(p)) {
      return;
    }
    try (var stream = Files.walk(p)) {
      stream
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              q -> {
                try {
                  Files.deleteIfExists(q);
                } catch (IOException ignore) {
                  // best-effort cleanup
                }
              });
    }
  }

  /**
   * Minimal {@link VectorIndexRepository} stub: enough for
   * {@link SegmentSearcher} to look up an index's distance metric, no
   * Flyway, no JDBI, no SQLite.
   */
  public static final class StubVectorIndexRepository implements VectorIndexRepository {

    private final Map<String, VectorIndex> byId = new HashMap<>();

    public void register(VectorIndex index) {
      byId.put(index.indexId(), index);
    }

    @Override
    public VectorIndex create(VectorIndex index) {
      byId.put(index.indexId(), index);
      return index;
    }

    @Override
    public Optional<VectorIndex> findById(String indexId) {
      return Optional.ofNullable(byId.get(indexId));
    }

    @Override
    public List<VectorIndex> listByBucket(String bucketId) {
      return byId.values().stream().filter(v -> v.bucketId().equals(bucketId)).toList();
    }

    @Override
    public void delete(String indexId) {
      byId.remove(indexId);
    }
  }
}
