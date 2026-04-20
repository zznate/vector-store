package io.github.zznate.vectorstore.engine.commit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.testsupport.EngineTestHarness;
import io.github.zznate.vectorstore.metadata.sidecar.TombstoneSidecar;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

/**
 * Exercises the per-segment tombstone merge invariants that
 * {@link CommitCoordinator#persistStagedTombstones} relies on: resolving
 * staged user IDs to ordinals via the cached ordinal map, unioning with
 * the existing persisted bitmap, and re-uploading. Bypasses
 * CommitCoordinator itself (that needs the full catalog wiring) and
 * drives the seams the app-level integration tests would cover in
 * Batch E.
 */
class TombstoneMergeTest {

  private EngineTestHarness harness;

  @BeforeEach
  void setUp() throws Exception {
    harness = EngineTestHarness.create();
  }

  @AfterEach
  void tearDown() throws Exception {
    harness.close();
  }

  @Test
  void ordinalsOfResolvesUserIdsViaCachedOrdinalMap() throws Exception {
    Segment segment = buildSegment("seg-1", 10);

    RoaringBitmap resolved = harness.searcher.ordinalsOf(segment, Set.of("u3", "u7", "missing"));
    assertThat(resolved.toArray()).containsExactly(3, 7);
  }

  @Test
  void ordinalsOfEmptyUserIdSetReturnsEmptyBitmap() throws Exception {
    Segment segment = buildSegment("seg-empty", 5);
    assertThat(harness.searcher.ordinalsOf(segment, Set.of()).isEmpty()).isTrue();
  }

  @Test
  void persistedSidecarStartsEmptyAfterBuild() throws Exception {
    Segment segment = buildSegment("seg-fresh", 4);
    try (InputStream in = harness.store.openSidecar(segment, "tombstones.roar")) {
      TombstoneSidecar sidecar = TombstoneSidecar.read(in);
      assertThat(sidecar.isEmpty()).isTrue();
    }
  }

  @Test
  void mergePersistsUnionAcrossTwoSuccessiveCommits() throws Exception {
    Segment segment = buildSegment("seg-merge", 10);

    // First "commit" of tombstones: drop u3 and u7 -> ordinals {3, 7}.
    RoaringBitmap firstAdditions = harness.searcher.ordinalsOf(segment, Set.of("u3", "u7"));
    TombstoneSidecar after1 = currentTombstones(segment).mergedWith(firstAdditions);
    harness.store.putSidecar(segment, "tombstones.roar", after1.toBytes());

    assertThat(currentTombstones(segment).bitmap().toArray()).containsExactly(3, 7);

    // Second "commit": drop u5 -> should be unioned with the prior bitmap.
    RoaringBitmap secondAdditions = harness.searcher.ordinalsOf(segment, Set.of("u5"));
    TombstoneSidecar after2 = currentTombstones(segment).mergedWith(secondAdditions);
    harness.store.putSidecar(segment, "tombstones.roar", after2.toBytes());

    assertThat(currentTombstones(segment).bitmap().toArray()).containsExactly(3, 5, 7);
  }

  @Test
  void mergeIsIdempotentWhenAdditionsAreAlreadyPresent() throws Exception {
    Segment segment = buildSegment("seg-idempotent", 6);
    RoaringBitmap additions = harness.searcher.ordinalsOf(segment, Set.of("u1", "u2"));

    TombstoneSidecar first = currentTombstones(segment).mergedWith(additions);
    harness.store.putSidecar(segment, "tombstones.roar", first.toBytes());

    // Same IDs again — resulting bitmap cardinality must not grow.
    TombstoneSidecar second = currentTombstones(segment).mergedWith(additions);
    harness.store.putSidecar(segment, "tombstones.roar", second.toBytes());

    assertThat(currentTombstones(segment).bitmap().getCardinality()).isEqualTo(2);
    assertThat(currentTombstones(segment).bitmap().toArray()).containsExactly(1, 2);
  }

  // ------------------------------------------------------------------

  private Segment buildSegment(String segmentId, int size) throws Exception {
    List<BufferEntry> entries = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      float[] vec = new float[4];
      vec[i % 4] = 1f;
      entries.add(new BufferEntry("u" + i, vec, Map.of()));
    }
    return harness.buildAndPublish(
        "demo",
        "widgets",
        segmentId,
        entries,
        4,
        DistanceMetric.COSINE,
        IndexBuildParams.defaults());
  }

  private TombstoneSidecar currentTombstones(Segment segment) throws Exception {
    try (InputStream in = harness.store.openSidecar(segment, "tombstones.roar")) {
      return TombstoneSidecar.read(in);
    }
  }
}
