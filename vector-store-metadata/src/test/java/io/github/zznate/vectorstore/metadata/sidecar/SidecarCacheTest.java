package io.github.zznate.vectorstore.metadata.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SidecarCacheTest {

  @Test
  void putThenGetIfPresentReturnsSameInstance() {
    SidecarCache cache = new SidecarCache(1 << 20, new SimpleMeterRegistry());
    SizedMock sidecar = new SizedMock(100);
    String key = SidecarCache.attributesKey("seg-1");

    cache.put(key, sidecar);
    assertThat(cache.getIfPresent(key)).isSameAs(sidecar);
  }

  @Test
  void missingEntryReturnsNull() {
    SidecarCache cache = new SidecarCache(1 << 20, new SimpleMeterRegistry());
    assertThat(cache.getIfPresent(SidecarCache.attributesKey("missing"))).isNull();
  }

  @Test
  void byteWeightedEvictionDropsEntriesUnderBudget() throws Exception {
    // Budget fits 2 × 100-byte entries.
    SidecarCache cache = new SidecarCache(200, new SimpleMeterRegistry());
    cache.put(SidecarCache.attributesKey("seg-1"), new SizedMock(100));
    cache.put(SidecarCache.attributesKey("seg-2"), new SizedMock(100));
    cache.put(SidecarCache.attributesKey("seg-3"), new SizedMock(100));

    // Caffeine's eviction is asynchronous; wait for it to settle.
    for (int i = 0; i < 20 && cache.estimatedSize() > 2; i++) {
      Thread.sleep(10);
    }
    assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
  }

  @Test
  void allSidecarKindsForOneSegmentHaveDistinctKeys() {
    SidecarCache cache = new SidecarCache(1 << 20, new SimpleMeterRegistry());
    SizedMock attrs = new SizedMock(50);
    SizedMock tomb = new SizedMock(30);
    SizedMock postings = new SizedMock(40);
    String attrKey = SidecarCache.attributesKey("seg-1");
    String tombKey = SidecarCache.tombstonesKey("seg-1");
    String postKey = SidecarCache.postingsKey("seg-1");
    assertThat(Set.of(attrKey, tombKey, postKey)).hasSize(3);

    cache.put(attrKey, attrs);
    cache.put(tombKey, tomb);
    cache.put(postKey, postings);
    assertThat(cache.getIfPresent(attrKey)).isSameAs(attrs);
    assertThat(cache.getIfPresent(tombKey)).isSameAs(tomb);
    assertThat(cache.getIfPresent(postKey)).isSameAs(postings);
  }

  @Test
  void invalidateRemovesOneEntryOnly() {
    SidecarCache cache = new SidecarCache(1 << 20, new SimpleMeterRegistry());
    String attrKey = SidecarCache.attributesKey("seg-1");
    String tombKey = SidecarCache.tombstonesKey("seg-1");
    cache.put(attrKey, new SizedMock(10));
    cache.put(tombKey, new SizedMock(10));

    cache.invalidate(attrKey);
    assertThat(cache.getIfPresent(attrKey)).isNull();
    assertThat(cache.getIfPresent(tombKey)).isNotNull();
  }

  private record SizedMock(long bytes) implements CachedSidecar {
    @Override
    public long sizeBytes() {
      return bytes;
    }
  }
}
