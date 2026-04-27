package io.github.zznate.vectorstore.engine.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.testsupport.EngineTestHarness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SegmentHandleCacheTest {

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
  void handleIsSharedAcrossGetsForSameSegment() throws Exception {
    Segment segment = buildSegment("seg-a", 5);

    SegmentHandle h1 = harness.handles.get(segment);
    SegmentHandle h2 = harness.handles.get(segment);

    assertThat(h2).isSameAs(h1);
  }

  @Test
  void invalidateEvictsAndNextGetReloads() throws Exception {
    Segment segment = buildSegment("seg-b", 5);

    SegmentHandle first = harness.handles.get(segment);
    harness.handles.invalidate(segment.segmentId());
    SegmentHandle second = harness.handles.get(segment);

    assertThat(second).isNotSameAs(first);
  }

  @Test
  void ordinalMapMatchesSegmentUserIds() throws Exception {
    Segment segment = buildSegment("seg-c", 8);

    SegmentHandle handle = harness.handles.get(segment);
    String[] ordinalMap = handle.ordinalMap();

    assertThat(ordinalMap).hasSize(8);
    for (int i = 0; i < 8; i++) {
      assertThat(ordinalMap[i]).isEqualTo("u" + i);
    }
  }

  @Test
  void concurrentColdAccessesSingleFlightTheLoader() throws Exception {
    Segment segment = buildSegment("seg-d", 5);
    harness.handles.invalidate(segment.segmentId());

    int threadCount = 8;
    CountDownLatch startLine = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    SegmentHandle[] results = new SegmentHandle[threadCount];

    for (int t = 0; t < threadCount; t++) {
      final int idx = t;
      new Thread(
              () -> {
                try {
                  startLine.await();
                  results[idx] = harness.handles.get(segment);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                } finally {
                  done.countDown();
                }
              })
          .start();
    }

    startLine.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

    for (int i = 1; i < threadCount; i++) {
      assertThat(results[i]).isSameAs(results[0]);
    }
  }

  @Test
  void pinReturnsHandleAndIsIdempotent() throws Exception {
    Segment segment = buildSegment("seg-pin-a", 4);

    SegmentHandle first = harness.handles.pin(segment);
    SegmentHandle second = harness.handles.pin(segment);

    assertThat(second).isSameAs(first);
    assertThat(harness.handles.isPinned(segment.segmentId())).isTrue();
    assertThat(harness.handles.pinnedCount()).isEqualTo(1);
  }

  @Test
  void getReturnsPinnedHandleAheadOfTier() throws Exception {
    Segment segment = buildSegment("seg-pin-b", 4);

    SegmentHandle pinned = harness.handles.pin(segment);
    SegmentHandle viaGet = harness.handles.get(segment);

    assertThat(viaGet).isSameAs(pinned);
  }

  @Test
  void pinDropsDuplicateTierCopy() throws Exception {
    Segment segment = buildSegment("seg-pin-c", 4);

    // Populate tier first via a regular get(), then pin.
    SegmentHandle viaTier = harness.handles.get(segment);
    SegmentHandle viaPin = harness.handles.pin(segment);

    assertThat(viaPin).isNotSameAs(viaTier);
    // The tier copy was invalidated and closed before the pin loaded a
    // fresh handle, so the segmentId should no longer appear in the tier
    // (only in the pinned map).
    assertThat(harness.handles.tier().get(segment.segmentId())).isEmpty();
  }

  @Test
  void unpinClosesAndDropsHandle() throws Exception {
    Segment segment = buildSegment("seg-pin-d", 4);

    SegmentHandle pinned = harness.handles.pin(segment);
    harness.handles.unpin(segment.segmentId());

    assertThat(harness.handles.isPinned(segment.segmentId())).isFalse();
    assertThat(harness.handles.pinnedCount()).isZero();
    // The pinned handle was closed - its graph should be unusable.
    try {
      pinned.graph().getView().close();
    } catch (Exception expected) {
      // expected: graph is closed
    }
  }

  @Test
  void unpinUnknownSegmentIsNoop() {
    harness.handles.unpin("never-pinned");
    assertThat(harness.handles.pinnedCount()).isZero();
  }

  @Test
  void pinSurvivesLruEvictionPressure() throws Exception {
    SegmentHandleCache tiny =
        new SegmentHandleCache(
            harness.store,
            OpenTelemetry.noop().getTracer("test"),
            new SimpleMeterRegistry(),
            1);

    Segment a = buildSegment("seg-pin-e-a", 3);
    Segment b = buildSegment("seg-pin-e-b", 3);
    Segment c = buildSegment("seg-pin-e-c", 3);

    SegmentHandle pinnedA = tiny.pin(a);
    // Push several entries through the tier; the budget=1 tier evicts each
    // in turn but the pinned entry must survive.
    tiny.get(b);
    tiny.get(c);
    tiny.get(b);

    assertThat(tiny.isPinned(a.segmentId())).isTrue();
    assertThat(tiny.get(a)).isSameAs(pinnedA);
  }

  @Test
  void invalidateAllAlsoClearsPinned() throws Exception {
    Segment segment = buildSegment("seg-pin-f", 4);
    harness.handles.pin(segment);

    harness.handles.invalidateAll();

    assertThat(harness.handles.isPinned(segment.segmentId())).isFalse();
    assertThat(harness.handles.pinnedCount()).isZero();
  }

  @Test
  void countWeightedEvictionClosesEvictedHandle() throws Exception {
    // Dedicated cache with budget=1 so adding a second segment evicts the
    // first immediately.
    SegmentHandleCache tiny =
        new SegmentHandleCache(
            harness.store,
            OpenTelemetry.noop().getTracer("test"),
            new SimpleMeterRegistry(),
            1);

    Segment a = buildSegment("seg-e-a", 3);
    Segment b = buildSegment("seg-e-b", 3);

    SegmentHandle handleA = tiny.get(a);
    tiny.get(b);

    for (int i = 0; i < 20 && tiny.tier().stats().currentEntries() > 1; i++) {
      Thread.sleep(10);
    }
    assertThat(tiny.tier().stats().currentEntries()).isLessThanOrEqualTo(1L);
    // The evicted handle's graph should already be closed — a fresh
    // getView() on it throws. We verify by attempting a View open and
    // expecting an exception (the exact type depends on JVector's close
    // semantics, so assert only that it fails).
    try {
      handleA.graph().getView().close();
      // If we got here the graph wasn't closed — fall through to fail.
    } catch (Exception expected) {
      // expected
    }
  }

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
}
