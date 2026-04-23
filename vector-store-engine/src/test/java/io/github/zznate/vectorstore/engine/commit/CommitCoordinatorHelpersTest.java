package io.github.zznate.vectorstore.engine.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.zznate.vectorstore.core.catalog.jdbi.CatalogCommitPublisher;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.engine.buffer.WriteBuffer;
import io.github.zznate.vectorstore.engine.build.SegmentBuilder;
import io.github.zznate.vectorstore.engine.search.Searcher;
import io.github.zznate.vectorstore.engine.tombstone.CatalogStagedTombstones;
import io.github.zznate.vectorstore.metadata.sidecar.SidecarLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises the named helpers on {@link CommitCoordinator} in isolation.
 * The full commit pipeline is covered end-to-end by the app-module
 * integration tests ({@code FilterAndTombstoneIT},
 * {@code CommitResilienceIT}, {@code StagedTombstoneDurabilityIT}); this
 * class asserts the contract of each helper independently so a future
 * regression in one phase lands a targeted red test, not an
 * integration-test stack trace a screen tall.
 */
class CommitCoordinatorHelpersTest {

  private static final String INDEX_ID = "demo/idx";
  private static final Instant NOW = Instant.parse("2026-04-24T00:00:00Z");

  @Test
  void computeWillBeActiveReturnsOnlyNewSegmentWhenManifestEmpty() {
    ManifestCache manifests = mock(ManifestCache.class);
    when(manifests.activeSegments(INDEX_ID)).thenReturn(List.of());
    BuiltSegment built = builtStub("seg-new", 5, 1024L);

    CommitCoordinator coord = newCoordinator(manifests);

    List<Segment> willBeActive =
        coord.computeWillBeActive(INDEX_ID, "seg-new", "bucket/idx/seg-new", built);

    assertThat(willBeActive)
        .hasSize(1)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.segmentId()).isEqualTo("seg-new");
              assertThat(s.state()).isEqualTo(SegmentState.ACTIVE);
              assertThat(s.vectorCount()).isEqualTo(5);
              assertThat(s.bytes()).isEqualTo(1024L);
            });
  }

  @Test
  void computeWillBeActiveAppendsNewSegmentAfterPreviousActive() {
    Segment seg1 = new Segment("seg-1", INDEX_ID, SegmentState.ACTIVE, 3, 100, "p/1", NOW);
    Segment seg2 = new Segment("seg-2", INDEX_ID, SegmentState.ACTIVE, 7, 200, "p/2", NOW);
    ManifestCache manifests = mock(ManifestCache.class);
    when(manifests.activeSegments(INDEX_ID)).thenReturn(List.of(seg1, seg2));
    BuiltSegment built = builtStub("seg-new", 5, 1024L);

    CommitCoordinator coord = newCoordinator(manifests);

    List<Segment> willBeActive =
        coord.computeWillBeActive(INDEX_ID, "seg-new", "bucket/idx/seg-new", built);

    assertThat(willBeActive)
        .extracting(Segment::segmentId)
        .containsExactly("seg-1", "seg-2", "seg-new");
  }

  @Test
  void applyStagedTombstonesAcrossNoOpsOnEmptyStagingSet() throws Exception {
    Searcher searcher = mock(Searcher.class);
    CommitCoordinator coord = newCoordinator(mock(ManifestCache.class), searcher);

    coord.applyStagedTombstonesAcross(
        List.of(seg("seg-1"), seg("seg-2")), Set.of(), "seg-new");

    verify(searcher, never()).ordinalsOf(any(), any());
  }

  @Test
  void finalizePublishAdvancesVersionAndPopulatesManifestCache() throws Exception {
    ManifestCache manifests = mock(ManifestCache.class);
    when(manifests.currentVersion(INDEX_ID)).thenReturn(Optional.of(3));
    CatalogCommitPublisher publisher = mock(CatalogCommitPublisher.class);
    CatalogStagedTombstones tombstones = mock(CatalogStagedTombstones.class);
    CommitCoordinator coord =
        newCoordinator(manifests, mock(Searcher.class), publisher, tombstones);

    VectorIndex index = index();
    BuiltSegment built = builtStub("seg-new", 5, 2048L);
    List<Segment> willBeActive = List.of(seg("seg-1"), seg("seg-new"));

    CommitOutcome outcome =
        coord.finalizePublish(index, "seg-new", built, willBeActive, Set.of("drop-1"));

    assertThat(outcome.segmentId()).isEqualTo("seg-new");
    assertThat(outcome.manifestVersion()).isEqualTo(4);
    verify(publisher)
        .publish(
            eq("seg-new"),
            eq(SegmentState.ACTIVE),
            eq(2048L),
            any(ManifestVersion.class),
            eq(INDEX_ID),
            eq(Set.of("drop-1")));
    verify(tombstones).recordUnstaged(INDEX_ID, 1);
    verify(manifests).populate(INDEX_ID, 4, willBeActive);
  }

  @Test
  void finalizePublishWrapsPublisherFailureAsCatalogPhase() {
    ManifestCache manifests = mock(ManifestCache.class);
    when(manifests.currentVersion(INDEX_ID)).thenReturn(Optional.of(1));
    CatalogCommitPublisher publisher = mock(CatalogCommitPublisher.class);
    doThrow(new RuntimeException("CAS conflict"))
        .when(publisher)
        .publish(anyString(), any(), anyLong(), any(), anyString(), any());
    SegmentRepository segments = mock(SegmentRepository.class);
    CommitCoordinator coord =
        newCoordinator(
            manifests,
            mock(Searcher.class),
            publisher,
            mock(CatalogStagedTombstones.class),
            segments);

    VectorIndex index = index();
    BuiltSegment built = builtStub("seg-new", 1, 64L);

    assertThatThrownBy(
            () ->
                coord.finalizePublish(
                    index, "seg-new", built, List.of(seg("seg-new")), Set.of()))
        .isInstanceOf(CommitFailedException.class)
        .hasFieldOrPropertyWithValue("phase", CommitCoordinator.PHASE_CATALOG);
    // Failure path retires the row even though state already fell through.
    verify(segments).updateState("seg-new", SegmentState.RETIRED);
  }

  // ------------------------------------------------------------------
  // constructor helpers

  private CommitCoordinator newCoordinator(ManifestCache manifests) {
    return newCoordinator(manifests, mock(Searcher.class));
  }

  private CommitCoordinator newCoordinator(ManifestCache manifests, Searcher searcher) {
    return newCoordinator(
        manifests,
        searcher,
        mock(CatalogCommitPublisher.class),
        mock(CatalogStagedTombstones.class));
  }

  private CommitCoordinator newCoordinator(
      ManifestCache manifests,
      Searcher searcher,
      CatalogCommitPublisher publisher,
      CatalogStagedTombstones tombstones) {
    return newCoordinator(manifests, searcher, publisher, tombstones, mock(SegmentRepository.class));
  }

  private CommitCoordinator newCoordinator(
      ManifestCache manifests,
      Searcher searcher,
      CatalogCommitPublisher publisher,
      CatalogStagedTombstones tombstones,
      SegmentRepository segments) {
    MeterRegistry registry = new SimpleMeterRegistry();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new CommitCoordinator(
        mock(WriteBuffer.class),
        mock(SegmentBuilder.class),
        mock(SegmentStore.class),
        segments,
        manifests,
        publisher,
        tombstones,
        searcher,
        mock(SidecarLoader.class),
        clock,
        registry);
  }

  private Segment seg(String id) {
    return new Segment(id, INDEX_ID, SegmentState.ACTIVE, 1, 10L, "p/" + id, NOW);
  }

  private BuiltSegment builtStub(String segmentId, long vectorCount, long bytes) {
    return new BuiltSegment(
        segmentId, Path.of("/tmp/noop"), vectorCount, bytes, IndexBuildParams.defaults(), NOW);
  }

  private VectorIndex index() {
    return new VectorIndex(INDEX_ID, "demo", "idx", 4, DistanceMetric.COSINE, "{}", NOW);
  }
}
