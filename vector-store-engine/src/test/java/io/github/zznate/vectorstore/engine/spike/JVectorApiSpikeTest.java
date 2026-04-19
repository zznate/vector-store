package io.github.zznate.vectorstore.engine.spike;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Throw-away spike pinning the JVector 4.0.0-rc.8 API shape we will wrap in
 * the production SegmentBuilder / Searcher. Deleted once the real engine code
 * lands. Do not add additional tests here; this file exists only to prove
 * the minimum build -> write -> load -> search loop works.
 */
class JVectorApiSpikeTest {

  private static final VectorTypeSupport VTS =
      VectorizationProvider.getInstance().getVectorTypeSupport();

  private Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("vs-jvector-spike-");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (tempDir != null && Files.exists(tempDir)) {
      try (var stream = Files.walk(tempDir)) {
        stream
            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (Exception ignore) {
                    // best-effort
                  }
                });
      }
    }
  }

  @Test
  void buildThenPersistThenSearchRoundTrip() throws Exception {
    int dim = 16;
    int count = 100;
    Random rng = new Random(42L);

    List<VectorFloat<?>> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      float[] raw = new float[dim];
      for (int j = 0; j < dim; j++) {
        raw[j] = rng.nextFloat() * 2f - 1f;
      }
      vectors.add(VTS.createFloatVector(raw));
    }
    RandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vectors, dim);

    try (GraphIndexBuilder builder =
        new GraphIndexBuilder(ravv, VectorSimilarityFunction.COSINE, 16, 50, 1.2f, 1.2f, false)) {
      var inMemoryGraph = builder.build(ravv);

      Path graphFile = tempDir.resolve("graph.jvec");
      OnDiskGraphIndex.write(inMemoryGraph, ravv, graphFile);
      assertThat(Files.size(graphFile)).isPositive();

      VectorFloat<?> query = vectors.get(7);

      try (var readerSupplier = new SimpleMappedReader.Supplier(graphFile.toAbsolutePath());
          var onDiskGraph = OnDiskGraphIndex.load(readerSupplier)) {
        var results =
            GraphSearcher.search(
                query, 5, ravv, VectorSimilarityFunction.COSINE, onDiskGraph, Bits.ALL);
        var nodes = results.getNodes();

        assertThat(nodes).hasSize(5);
        assertThat(nodes[0].node).isEqualTo(7);
      }

      // Search again using the graph's own view as the RAVV — the production
      // path once a segment is reopened from disk without the original
      // in-memory vectors.
      try (var readerSupplier = new SimpleMappedReader.Supplier(graphFile.toAbsolutePath());
          var onDiskGraph = OnDiskGraphIndex.load(readerSupplier);
          var view = onDiskGraph.getView()) {
        var results =
            GraphSearcher.search(
                query, 5, view, VectorSimilarityFunction.COSINE, onDiskGraph, Bits.ALL);
        var nodes = results.getNodes();

        assertThat(nodes).hasSize(5);
        assertThat(nodes[0].node).isEqualTo(7);
      }
    }
  }
}
