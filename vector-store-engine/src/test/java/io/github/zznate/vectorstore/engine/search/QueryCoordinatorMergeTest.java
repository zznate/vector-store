package io.github.zznate.vectorstore.engine.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.github.zznate.vectorstore.engine.tombstone.CatalogStagedTombstones;
import io.github.zznate.vectorstore.metadata.filter.FilterCompiler;
import io.github.zznate.vectorstore.metadata.sidecar.AttributeSidecar;
import io.github.zznate.vectorstore.metadata.sidecar.SidecarLoader;
import io.github.zznate.vectorstore.metadata.sidecar.TombstoneSidecar;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link QueryCoordinator} merges per-segment hit lists into
 * a single top-k ordered by score, independent of segment count or
 * segment ordering. Uses a mocked {@link Searcher} and a stub
 * {@link SidecarLoader} so the merge algorithm is exercised in isolation;
 * the full build + search + filter path is covered by
 * {@code SegmentBuilderRecallTest} and by the MinIO integration tests.
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
    ManifestCache resolver = mock(ManifestCache.class);
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of(segA, segB));

    Searcher searcher = mock(Searcher.class);
    when(searcher.search(eq(segA), eq(QUERY), anyInt(), any(), any()))
        .thenReturn(
            List.of(
                new ScoredOrdinal(0, "a", 0.9f),
                new ScoredOrdinal(1, "b", 0.5f),
                new ScoredOrdinal(2, "c", 0.3f)));
    when(searcher.search(eq(segB), eq(QUERY), anyInt(), any(), any()))
        .thenReturn(
            List.of(
                new ScoredOrdinal(0, "d", 0.8f),
                new ScoredOrdinal(1, "e", 0.4f),
                new ScoredOrdinal(2, "f", 0.2f)));

    QueryCoordinator coordinator = newCoordinator(searcher, resolver);

    List<ScoredHit> merged =
        coordinator.query(INDEX_ID, QUERY, 3, null, SearchTuning.defaults(3));

    assertThat(merged).extracting(ScoredHit::userId).containsExactly("a", "d", "b");
    assertThat(merged).extracting(ScoredHit::score).containsExactly(0.9f, 0.8f, 0.5f);
  }

  @Test
  void mergeHonoursSegmentCountOfOne() {
    ManifestCache resolver = mock(ManifestCache.class);
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of(segA));

    Searcher searcher = mock(Searcher.class);
    when(searcher.search(eq(segA), eq(QUERY), anyInt(), any(), any()))
        .thenReturn(
            List.of(
                new ScoredOrdinal(0, "a", 0.9f),
                new ScoredOrdinal(1, "b", 0.5f),
                new ScoredOrdinal(2, "c", 0.3f)));

    QueryCoordinator coordinator = newCoordinator(searcher, resolver);

    assertThat(coordinator.query(INDEX_ID, QUERY, 2, null, SearchTuning.defaults(2)))
        .extracting(ScoredHit::userId)
        .containsExactly("a", "b");
  }

  @Test
  void zeroActiveSegmentsReturnsEmptyHits() {
    ManifestCache resolver = mock(ManifestCache.class);
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of());

    QueryCoordinator coordinator = newCoordinator(mock(Searcher.class), resolver);

    assertThat(coordinator.query(INDEX_ID, QUERY, 5, null, SearchTuning.defaults(5))).isEmpty();
  }

  private QueryCoordinator newCoordinator(Searcher searcher, ManifestCache resolver) {
    SidecarLoader loader = mock(SidecarLoader.class);
    // All segments report empty tombstones and empty attributes so the
    // coordinator short-circuits to Bits.ALL and enriches hits with an
    // empty attributes map.
    when(loader.tombstones(any(Segment.class))).thenReturn(TombstoneSidecar.empty());
    when(loader.attributes(any(Segment.class)))
        .thenReturn(AttributeSidecar.of(List.of(Map.of(), Map.of(), Map.of())));

    MeterRegistry registry = new SimpleMeterRegistry();
    Tracer tracer = OpenTelemetry.noop().getTracer("test");
    FilterCompiler compiler = new FilterCompiler(registry, tracer);

    StagedTombstoneRepository stagedRepo = mock(StagedTombstoneRepository.class);
    when(stagedRepo.snapshot(any(String.class))).thenReturn(Set.of());
    CatalogStagedTombstones tombstones =
        new CatalogStagedTombstones(stagedRepo, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), registry);

    CachePolicyEnforcer enforcer = mock(CachePolicyEnforcer.class);
    return new QueryCoordinator(
        resolver, searcher, enforcer, tombstones, loader, compiler, tracer, registry);
  }
}
