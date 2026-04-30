package io.github.zznate.vectorstore.engine.build;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jbellis.jvector.util.Bits;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.search.ScoredOrdinal;
import io.github.zznate.vectorstore.engine.search.SearchTuning;
import io.github.zznate.vectorstore.engine.testsupport.EngineTestHarness;
import io.github.zznate.vectorstore.testsupport.fixtures.FixtureChunk;
import io.github.zznate.vectorstore.testsupport.fixtures.FixtureQuery;
import io.github.zznate.vectorstore.testsupport.fixtures.RecallFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parameter sweep across {@link IndexBuildParams} corners. Builds one
 * segment per corner from the Wikipedia + MiniLM-L6-v2 recall fixture,
 * runs the 20 fixture queries, and records recall, build wall time,
 * and on-disk size. The {@code @AfterAll} hook prints a comparison
 * table to stdout.
 *
 * <p>The fixture is small (184 chunks, 20 queries) so the full sweep
 * runs in under a minute even with several corners. Adding a corner is
 * a one-line append to {@link #corners()} — the harness picks up any
 * new {@link IndexBuildParams} variant without further wiring.
 *
 * <p>Smoke assertion only: each corner must build successfully and
 * return at least one hit per query. There is no hard recall floor
 * here — different params produce different floors, and the point of
 * the sweep is to compare them against each other rather than against
 * a single threshold. {@code SegmentBuilderRecallTest} owns the
 * regression gate on the default params.
 */
@Tag("paramsweep")
class IndexBuildParamSweepTest {

  private static final int TOP_K = 5;
  private static final List<Result> RESULTS = new CopyOnWriteArrayList<>();

  @ParameterizedTest(name = "[{index}] m={0} beamWidth={1} addHierarchy={2}")
  @MethodSource("corners")
  void sweepCorner(int m, int beamWidth, boolean addHierarchy) throws Exception {
    IndexBuildParams params = withCorner(m, beamWidth, addHierarchy);
    try (EngineTestHarness harness = EngineTestHarness.create()) {
      List<FixtureChunk> corpus = RecallFixture.loadCorpus();
      List<FixtureQuery> queries = RecallFixture.loadQueries();
      int dim = corpus.get(0).embedding().length;

      Map<String, String> chunkToArticle = new HashMap<>(corpus.size());
      List<BufferEntry> entries = new ArrayList<>(corpus.size());
      for (FixtureChunk chunk : corpus) {
        chunkToArticle.put(chunk.id(), chunk.articleSlug());
        entries.add(new BufferEntry(chunk.id(), chunk.embedding(), Map.of()));
      }

      String segmentId = "seg-" + UUID.randomUUID();
      long buildStart = System.nanoTime();
      Segment segment =
          harness.buildAndPublish(
              "demo", "sweep", segmentId, entries, dim, DistanceMetric.COSINE, params);
      long buildMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - buildStart);

      Path segDir = harness.root.resolve(segment.objectPrefix());
      long graphBytes = Files.size(segDir.resolve("graph.jvec"));
      long postingsBytes =
          Files.exists(segDir.resolve("postings.bin"))
              ? Files.size(segDir.resolve("postings.bin"))
              : 0L;

      int top1 = 0;
      int top5Majority = 0;
      for (FixtureQuery query : queries) {
        List<ScoredOrdinal> hits =
            harness.searcher.search(
                segment, query.embedding(), TOP_K, Bits.ALL, SearchTuning.defaults(TOP_K));
        assertThat(hits).as("query %s returned no hits", query.id()).isNotEmpty();
        String topSlug = chunkToArticle.get(hits.get(0).userId());
        if (query.expectedArticleSlug().equals(topSlug)) {
          top1++;
        }
        long inExpected =
            hits.stream()
                .map(h -> chunkToArticle.get(h.userId()))
                .filter(query.expectedArticleSlug()::equals)
                .count();
        if (inExpected > TOP_K / 2) {
          top5Majority++;
        }
      }

      RESULTS.add(
          new Result(params, queries.size(), top1, top5Majority, buildMs, graphBytes, postingsBytes));
    }
  }

  @AfterAll
  static void printSweepTable() {
    if (RESULTS.isEmpty()) {
      return;
    }
    System.out.printf("%n=== IndexBuildParam Sweep (%d corners) ===%n", RESULTS.size());
    String fmt = "%2s  %-9s  %-5s  %-5s  %-5s    %-7s  %-9s    %-9s  %-9s  %-9s%n";
    System.out.printf(
        fmt, "m", "beamWidth", "ovrfl", "alpha", "hier", "top1/N", "top5Maj/N", "buildMs", "graphKB", "postsKB");
    System.out.printf(
        fmt,
        "--", "---------", "-----", "-----", "-----", "-------", "---------", "---------", "---------", "---------");
    RESULTS.stream()
        .sorted(Comparator.comparingInt(Result::scoreOrder).reversed())
        .forEach(
            r ->
                System.out.printf(
                    fmt,
                    Integer.toString(r.params.m()),
                    Integer.toString(r.params.beamWidth()),
                    String.format("%.2f", r.params.neighborOverflow()),
                    String.format("%.2f", r.params.alpha()),
                    Boolean.toString(r.params.addHierarchy()),
                    String.format("%d/%d", r.top1Correct, r.totalQueries),
                    String.format("%d/%d", r.top5MajorityCorrect, r.totalQueries),
                    Long.toString(r.buildMs),
                    Long.toString(r.graphBytes / 1024L),
                    Long.toString(r.postingsBytes / 1024L)));
    RESULTS.clear();
  }

  /**
   * Corners to evaluate. Each tuple is {@code (m, beamWidth,
   * addHierarchy)} — the other knobs default to {@link
   * IndexBuildParams#defaults()}. Add one line to evaluate a new
   * corner; no other code change required.
   */
  static Stream<org.junit.jupiter.params.provider.Arguments> corners() {
    return Stream.of(
        args(32, 100, false), // beamWidth halved vs default
        args(32, 200, false), // current default
        args(32, 100, true), //  upstream tutorial defaults
        args(32, 200, true), //  hierarchy on, default beam
        args(16, 100, true), //  lower m, hierarchy on
        args(64, 100, true)); // higher m, hierarchy on
  }

  private static org.junit.jupiter.params.provider.Arguments args(
      int m, int beamWidth, boolean addHierarchy) {
    return org.junit.jupiter.params.provider.Arguments.of(m, beamWidth, addHierarchy);
  }

  private static IndexBuildParams withCorner(int m, int beamWidth, boolean addHierarchy) {
    IndexBuildParams d = IndexBuildParams.defaults();
    return new IndexBuildParams(
        m,
        beamWidth,
        d.neighborOverflow(),
        d.alpha(),
        d.pqSubspaces(),
        d.pqSubspaceClusters(),
        addHierarchy,
        d.cachePolicy(),
        d.cacheBytes());
  }

  /** Result row aggregated for the @AfterAll print-out. */
  private record Result(
      IndexBuildParams params,
      int totalQueries,
      int top1Correct,
      int top5MajorityCorrect,
      long buildMs,
      long graphBytes,
      long postingsBytes) {

    /** Descending order by top-1 then top-5 majority for the print-out. */
    int scoreOrder() {
      return top1Correct * 100 + top5MajorityCorrect;
    }
  }
}
