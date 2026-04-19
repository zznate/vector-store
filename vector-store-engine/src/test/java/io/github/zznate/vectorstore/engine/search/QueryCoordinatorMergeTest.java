package io.github.zznate.vectorstore.engine.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.zznate.vectorstore.core.catalog.manifest.ManifestResolver;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.engine.tombstone.InMemoryTombstones;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link QueryCoordinator} merges per-segment hit lists into
 * a single top-k ordered by score, independent of segment count or
 * segment ordering. Uses a mocked {@link Searcher} so the merge algorithm
 * is exercised in isolation — the full build + search + merge path is
 * covered by {@code SegmentBuilderRecallTest}.
 */
class QueryCoordinatorMergeTest {

  private static final String INDEX_ID = "demo/merge";
  private static final float[] QUERY = new float[] {1.0f, 0.0f};

  private final Segment segA =
      new Segment("seg-a", INDEX_ID, SegmentState.ACTIVE, 3, 100, "p/a", Instant.EPOCH);
  private final Segment segB =
      new Segment("seg-b", INDEX_ID, SegmentState.ACTIVE, 3, 100, "p/b", Instant.EPOCH);

  @Test
  void topKInterleavesSegmentsByScore() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of(segA, segB));

    Searcher searcher = mock(Searcher.class);
    when(searcher.buildAcceptMask(any(Segment.class), any())).thenReturn(io.github.jbellis.jvector.util.Bits.ALL);
    when(searcher.search(eq(segA), eq(QUERY), anyInt(), any()))
        .thenReturn(
            List.of(
                new ScoredOrdinal(0, "a", 0.9f),
                new ScoredOrdinal(1, "b", 0.5f),
                new ScoredOrdinal(2, "c", 0.3f)));
    when(searcher.search(eq(segB), eq(QUERY), anyInt(), any()))
        .thenReturn(
            List.of(
                new ScoredOrdinal(0, "d", 0.8f),
                new ScoredOrdinal(1, "e", 0.4f),
                new ScoredOrdinal(2, "f", 0.2f)));

    QueryCoordinator coordinator =
        new QueryCoordinator(
            resolver,
            searcher,
            new InMemoryTombstones(),
            OpenTelemetry.noop().getTracer("test"),
            new SimpleMeterRegistry());

    List<ScoredOrdinal> merged = coordinator.query(INDEX_ID, QUERY, 3);

    assertThat(merged).extracting(ScoredOrdinal::userId).containsExactly("a", "d", "b");
    assertThat(merged).extracting(ScoredOrdinal::score).containsExactly(0.9f, 0.8f, 0.5f);
  }

  @Test
  void mergeHonoursSegmentCountOfOne() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of(segA));

    Searcher searcher = mock(Searcher.class);
    when(searcher.buildAcceptMask(any(Segment.class), any())).thenReturn(io.github.jbellis.jvector.util.Bits.ALL);
    when(searcher.search(eq(segA), eq(QUERY), anyInt(), any()))
        .thenReturn(
            List.of(
                new ScoredOrdinal(0, "a", 0.9f),
                new ScoredOrdinal(1, "b", 0.5f),
                new ScoredOrdinal(2, "c", 0.3f)));

    QueryCoordinator coordinator =
        new QueryCoordinator(
            resolver,
            searcher,
            new InMemoryTombstones(),
            OpenTelemetry.noop().getTracer("test"),
            new SimpleMeterRegistry());

    assertThat(coordinator.query(INDEX_ID, QUERY, 2))
        .extracting(ScoredOrdinal::userId)
        .containsExactly("a", "b");
  }

  @Test
  void zeroActiveSegmentsReturnsEmptyHits() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of());

    QueryCoordinator coordinator =
        new QueryCoordinator(
            resolver,
            mock(Searcher.class),
            new InMemoryTombstones(),
            OpenTelemetry.noop().getTracer("test"),
            new SimpleMeterRegistry());

    assertThat(coordinator.query(INDEX_ID, QUERY, 5)).isEmpty();
  }
}
