package io.github.zznate.vectorstore.engine.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.CachePolicy;
import io.github.zznate.vectorstore.core.cache.CachePolicyResolver;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.testsupport.EngineTestHarness;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CachePolicyEnforcerTest {

  private EngineTestHarness harness;
  private CachePolicyResolver resolver;
  private CachePolicyEnforcer enforcer;

  @BeforeEach
  void setUp() throws Exception {
    harness = EngineTestHarness.create();
    resolver = new CachePolicyResolver(harness.indexes);
    enforcer = new CachePolicyEnforcer(resolver, harness.handles, harness.meterRegistry);
  }

  @AfterEach
  void tearDown() throws Exception {
    enforcer.invalidateAll();
    harness.close();
  }

  @Test
  void smartIndexPinsNothing() throws Exception {
    Segment seg = buildSegment("smart", "seg-a", 4, IndexBuildParams.defaults());

    enforcer.onQuery(seg.indexId(), List.of(seg));

    assertThat(harness.handles.isPinned(seg.segmentId())).isFalse();
    assertThat(enforcer.residentSegmentCount(seg.indexId())).isZero();
  }

  @Test
  void minimalIndexPinsNothing() throws Exception {
    Segment seg =
        buildSegment(
            "minimal",
            "seg-a",
            4,
            new IndexBuildParams(
                32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.MINIMAL, null));

    enforcer.onQuery(seg.indexId(), List.of(seg));

    assertThat(harness.handles.isPinned(seg.segmentId())).isFalse();
  }

  @Test
  void residentIndexPinsEveryActiveSegment() throws Exception {
    IndexBuildParams resident =
        new IndexBuildParams(
            32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    Segment a = buildSegment("hot", "seg-a", 4, resident);
    Segment b = buildSegment("hot", "seg-b", 4, resident);

    enforcer.onQuery(a.indexId(), List.of(a, b));

    assertThat(harness.handles.isPinned(a.segmentId())).isTrue();
    assertThat(harness.handles.isPinned(b.segmentId())).isTrue();
    assertThat(enforcer.residentSegmentCount(a.indexId())).isEqualTo(2);
  }

  @Test
  void residentOnQueryIsIdempotent() throws Exception {
    IndexBuildParams resident =
        new IndexBuildParams(
            32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    Segment a = buildSegment("hot-idem", "seg-a", 4, resident);

    enforcer.onQuery(a.indexId(), List.of(a));
    enforcer.onQuery(a.indexId(), List.of(a));
    enforcer.onQuery(a.indexId(), List.of(a));

    assertThat(enforcer.residentSegmentCount(a.indexId())).isEqualTo(1);
  }

  @Test
  void segmentDroppedFromActiveListIsUnpinned() throws Exception {
    IndexBuildParams resident =
        new IndexBuildParams(
            32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    Segment a = buildSegment("hot-drop", "seg-a", 4, resident);
    Segment b = buildSegment("hot-drop", "seg-b", 4, resident);

    enforcer.onQuery(a.indexId(), List.of(a, b));
    enforcer.onQuery(a.indexId(), List.of(a));

    assertThat(harness.handles.isPinned(a.segmentId())).isTrue();
    assertThat(harness.handles.isPinned(b.segmentId())).isFalse();
    assertThat(enforcer.residentSegmentCount(a.indexId())).isEqualTo(1);
  }

  @Test
  void onSegmentRetiredUnpinsAndRemovesFromIndex() throws Exception {
    IndexBuildParams resident =
        new IndexBuildParams(
            32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    Segment a = buildSegment("hot-retire", "seg-a", 4, resident);

    enforcer.onQuery(a.indexId(), List.of(a));
    enforcer.onSegmentRetired(a.segmentId());

    assertThat(harness.handles.isPinned(a.segmentId())).isFalse();
    assertThat(enforcer.residentSegmentCount(a.indexId())).isZero();
  }

  @Test
  void invalidateIndexUnpinsAndZerosGauges() throws Exception {
    IndexBuildParams resident =
        new IndexBuildParams(
            32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    Segment a = buildSegment("hot-inv", "seg-a", 4, resident);

    enforcer.onQuery(a.indexId(), List.of(a));
    enforcer.invalidateIndex(a.indexId());

    assertThat(harness.handles.isPinned(a.segmentId())).isFalse();
    assertThat(enforcer.residentSegmentCount(a.indexId())).isZero();
  }

  @Test
  void residentByteGaugeReflectsPinnedSegments() throws Exception {
    IndexBuildParams resident =
        new IndexBuildParams(
            32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    Segment a = buildSegment("hot-gauge", "seg-a", 4, resident);
    Segment b = buildSegment("hot-gauge", "seg-b", 4, resident);

    enforcer.onQuery(a.indexId(), List.of(a, b));

    double bytesGauge =
        harness
            .meterRegistry
            .get(CachePolicyEnforcer.GAUGE_RESIDENT_BYTES)
            .tags(Tags.of(CachePolicyEnforcer.TAG_INDEX_ID, a.indexId()))
            .gauge()
            .value();
    double countGauge =
        harness
            .meterRegistry
            .get(CachePolicyEnforcer.GAUGE_RESIDENT_SEGMENTS)
            .tags(Tags.of(CachePolicyEnforcer.TAG_INDEX_ID, a.indexId()))
            .gauge()
            .value();

    assertThat(bytesGauge).isEqualTo((double) (a.bytes() + b.bytes()));
    assertThat(countGauge).isEqualTo(2.0);
  }

  @Test
  void unknownIndexLogsAndContinues() {
    Segment ghost =
        new Segment(
            "ghost-seg",
            "demo/ghost",
            SegmentState.ACTIVE,
            1,
            100,
            "demo/ghost/ghost-seg",
            Instant.EPOCH);

    enforcer.onQuery("demo/ghost", List.of(ghost));

    assertThat(harness.handles.isPinned("ghost-seg")).isFalse();
  }

  private Segment buildSegment(
      String indexName, String segmentId, int size, IndexBuildParams params) throws Exception {
    String bucket = "demo";
    String indexId = bucket + "/" + indexName;
    if (harness.indexes.findById(indexId).isEmpty()) {
      harness.indexes.register(
          new VectorIndex(
              indexId,
              bucket,
              indexName,
              4,
              DistanceMetric.COSINE,
              params.toJson(),
              Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    }
    List<BufferEntry> entries = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      float[] vec = new float[4];
      vec[i % 4] = 1f;
      entries.add(new BufferEntry("u" + i, vec, Map.of()));
    }
    return harness.buildAndPublish(bucket, indexName, segmentId, entries, 4, DistanceMetric.COSINE, params);
  }
}
