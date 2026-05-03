package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class HeapCacheTierTest {

  @Test
  void putThenGetReturnsCachedValueAndIncrementsHit() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(1 << 20, v -> v.length)
            .meterRegistry(registry)
            .build();

    byte[] payload = {1, 2, 3};
    tier.put("k", payload);

    assertThat(tier.get("k")).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 2, 3));
    assertThat(registry.counter("vectorstore.cache.hit", "tier", "l1_heap", "cache_name", "test").count())
        .isEqualTo(1.0);
  }

  @Test
  void missIncrementsMissCounter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(1 << 20, v -> v.length)
            .meterRegistry(registry)
            .build();

    assertThat(tier.get("missing")).isEmpty();
    assertThat(registry.counter("vectorstore.cache.miss", "tier", "l1_heap", "cache_name", "test").count())
        .isEqualTo(1.0);
  }

  @Test
  void byteWeightedEvictionTriggersEvictionCounter() throws InterruptedException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(200, v -> v.length)
            .meterRegistry(registry)
            .build();

    tier.put("a", new byte[100]);
    tier.put("b", new byte[100]);
    tier.put("c", new byte[100]);

    // Caffeine evicts asynchronously — pulse until settled.
    for (int i = 0; i < 20 && tier.stats().currentEntries() > 2; i++) {
      Thread.sleep(10);
    }
    assertThat(tier.stats().currentEntries()).isLessThanOrEqualTo(2L);
    assertThat(registry.counter("vectorstore.cache.eviction", "tier", "l1_heap", "cache_name", "test").count())
        .isGreaterThan(0.0);
  }

  @Test
  void countWeightedModeBoundsEntries() throws InterruptedException {
    HeapCacheTier<String, String> tier =
        HeapCacheTier.<String, String>builder("test").countWeighted(2).build();

    tier.put("a", "1");
    tier.put("b", "2");
    tier.put("c", "3");

    for (int i = 0; i < 20 && tier.stats().currentEntries() > 2; i++) {
      Thread.sleep(10);
    }
    assertThat(tier.stats().currentEntries()).isLessThanOrEqualTo(2L);
  }

  @Test
  void invalidateRemovesEntry() {
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(1 << 20, v -> v.length)
            .build();
    tier.put("k", new byte[] {1});

    tier.invalidate("k");

    assertThat(tier.get("k")).isEmpty();
  }

  @Test
  void invalidateAllClearsEveryEntry() {
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(1 << 20, v -> v.length)
            .build();
    tier.put("a", new byte[10]);
    tier.put("b", new byte[10]);

    tier.invalidateAll();

    assertThat(tier.get("a")).isEmpty();
    assertThat(tier.get("b")).isEmpty();
  }

  @Test
  void statsReflectBytesAndEntries() {
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(1 << 20, v -> v.length)
            .build();

    tier.put("a", new byte[32]);
    tier.put("b", new byte[64]);

    CacheTierStats stats = tier.stats();
    assertThat(stats.currentBytes()).isEqualTo(32 + 64);
    assertThat(stats.currentEntries()).isEqualTo(2L);
    assertThat(stats.maxBytes()).isEqualTo(1L << 20);
  }

  @Test
  void builderWithoutBudgetThrows() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> HeapCacheTier.<String, byte[]>builder("test").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires a byte or entry budget");
  }

  @Test
  void evictionListenerIsSynchronousAndCounterIsExact() throws InterruptedException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(200, v -> v.length)
            .meterRegistry(registry)
            .build();

    tier.put("a", new byte[100]);
    tier.put("b", new byte[100]);
    tier.put("c", new byte[100]);

    // Eviction triggering is itself amortized inside Caffeine; once
    // estimatedSize() drops to 2 the synchronous evictionListener has
    // fired and the counter must already reflect exactly one eviction.
    for (int i = 0; i < 20 && tier.stats().currentEntries() > 2; i++) {
      Thread.sleep(10);
    }
    assertThat(tier.stats().currentEntries()).isEqualTo(2L);
    assertThat(
            registry
                .counter("vectorstore.cache.eviction", "tier", "l1_heap", "cache_name", "test")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void explicitInvalidateDecrementsCurrentBytes() throws InterruptedException {
    HeapCacheTier<String, byte[]> tier =
        HeapCacheTier.<String, byte[]>builder("test")
            .byteWeighted(1 << 20, v -> v.length)
            .build();

    tier.put("a", new byte[32]);
    tier.put("b", new byte[64]);
    assertThat(tier.stats().currentBytes()).isEqualTo(96L);

    tier.invalidate("a");
    tier.invalidate("b");

    // removalListener is async; poll until the byte gauge settles. The
    // assertion guards against the regression of moving the decrement
    // into evictionListener (which never fires for EXPLICIT).
    for (int i = 0; i < 50 && tier.stats().currentBytes() != 0L; i++) {
      Thread.sleep(10);
    }
    assertThat(tier.stats().currentBytes()).isEqualTo(0L);
  }

  @Test
  void registersExpectedMetricSchema() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    HeapCacheTier.<String, byte[]>builder("test")
        .byteWeighted(1 << 20, v -> v.length)
        .meterRegistry(registry)
        .build();

    Tags expectedTags = Tags.of("tier", "l1_heap", "cache_name", "test");
    assertThat(registry.find("vectorstore.cache.hit").tags(expectedTags).counter()).isNotNull();
    assertThat(registry.find("vectorstore.cache.miss").tags(expectedTags).counter()).isNotNull();
    assertThat(registry.find("vectorstore.cache.eviction").tags(expectedTags).counter())
        .isNotNull();
  }
}
