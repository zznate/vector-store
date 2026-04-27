package io.github.zznate.vectorstore.storage.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.OffHeapArenaL2Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the tiered behaviour of {@link BlockCache} when an
 * {@link L2Provider} is wired in. The L1-only path is covered by
 * {@code BlockCachingRandomAccessReaderTest}; this class focuses on the
 * L1↔L2 promotion / demotion and the metric tags emitted from each tier.
 */
class BlockCacheTieredTest {

  private SimpleMeterRegistry registry;
  private OffHeapArenaL2Provider l2;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    l2 = new OffHeapArenaL2Provider(1L << 20, registry, BlockCache.CACHE_NAME);
  }

  @AfterEach
  void tearDown() {
    l2.close();
  }

  @Test
  void putWritesThroughToBothTiers() {
    BlockCache cache = new BlockCache(1L << 20, registry, l2);
    BlockKey key = new BlockKey("bucket/index/seg/graph", 0L);

    cache.put(key, new byte[] {1, 2, 3});

    assertThat(cache.tier().stats().currentEntries()).isEqualTo(1L);
    assertThat(l2.stats().currentEntries()).isEqualTo(1L);
  }

  @Test
  void l1MissL2HitPromotesToL1AndReturnsBytes() {
    BlockCache cache = new BlockCache(1L << 20, registry, l2);
    BlockKey key = new BlockKey("bucket/index/seg/graph", 7L);

    // Seed only L2 (simulating L1 eviction).
    l2.put(BlockCache.toL2Key(key), new byte[] {9, 9, 9});
    assertThat(cache.tier().stats().currentEntries()).isZero();

    byte[] result = cache.getIfPresent(key);

    assertThat(result).containsExactly(9, 9, 9);
    // Promotion path: L1 now holds the block.
    assertThat(cache.tier().stats().currentEntries()).isEqualTo(1L);
    // L1 missed, L2 hit — counters reflect both.
    assertThat(registry
            .counter("vectorstore.cache.miss", "tier", "l1_heap", "cache_name", "block").count())
        .isEqualTo(1.0);
    assertThat(registry
            .counter("vectorstore.cache.hit", "tier", "l2_offheap", "cache_name", "block").count())
        .isEqualTo(1.0);
  }

  @Test
  void l1HitDoesNotConsultL2() {
    BlockCache cache = new BlockCache(1L << 20, registry, l2);
    BlockKey key = new BlockKey("bucket/index/seg/graph", 3L);

    cache.put(key, new byte[] {1});
    cache.getIfPresent(key); // warm L1
    long l2Hits = (long) registry
        .counter("vectorstore.cache.hit", "tier", "l2_offheap", "cache_name", "block").count();
    long l2Misses = (long) registry
        .counter("vectorstore.cache.miss", "tier", "l2_offheap", "cache_name", "block").count();

    cache.getIfPresent(key);

    // L1 served the read; L2 counters are unchanged.
    assertThat((long) registry
            .counter("vectorstore.cache.hit", "tier", "l2_offheap", "cache_name", "block").count())
        .isEqualTo(l2Hits);
    assertThat((long) registry
            .counter("vectorstore.cache.miss", "tier", "l2_offheap", "cache_name", "block").count())
        .isEqualTo(l2Misses);
  }

  @Test
  void missesBothTiersReturnsNullAndIncrementsBothMissCounters() {
    BlockCache cache = new BlockCache(1L << 20, registry, l2);
    BlockKey key = new BlockKey("bucket/index/seg/graph", 99L);

    byte[] result = cache.getIfPresent(key);

    assertThat(result).isNull();
    assertThat(registry
            .counter("vectorstore.cache.miss", "tier", "l1_heap", "cache_name", "block").count())
        .isEqualTo(1.0);
    assertThat(registry
            .counter("vectorstore.cache.miss", "tier", "l2_offheap", "cache_name", "block").count())
        .isEqualTo(1.0);
  }

  @Test
  void invalidateAllClearsBothTiers() {
    BlockCache cache = new BlockCache(1L << 20, registry, l2);
    cache.put(new BlockKey("bucket/index/seg/graph", 0L), new byte[] {1});
    cache.put(new BlockKey("bucket/index/seg/graph", 1L), new byte[] {2});
    assertThat(l2.stats().currentEntries()).isEqualTo(2L);

    cache.invalidateAll();

    assertThat(cache.tier().stats().currentEntries()).isZero();
    assertThat(l2.stats().currentEntries()).isZero();
  }

  @Test
  void l1OnlyConstructorOmitsL2() {
    // Fresh registry so the @BeforeEach L2 instance hasn't already
    // registered its meters under the inspected name+tags.
    SimpleMeterRegistry localRegistry = new SimpleMeterRegistry();
    BlockCache cache = new BlockCache(1L << 20, localRegistry);
    cache.put(new BlockKey("bucket/index/seg/graph", 0L), new byte[] {1});

    assertThat(cache.l2()).isNull();
    // No L2 metrics emitted because no L2 is wired.
    assertThat(
            localRegistry
                .find("vectorstore.cache.hit")
                .tag("tier", "l2_offheap")
                .counter())
        .isNull();
  }
}
