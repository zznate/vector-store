package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlabOffHeapL2ProviderTest {

  // 8 MiB total, 1 MiB per shard, 64 KiB block size → 16 slots per shard.
  private static final long MAX_BYTES = 8L << 20;
  private static final int BLOCK_SIZE = 64 * 1024;

  @Test
  void putThenGetRoundTripsBytesAndIncrementsHit() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, registry, "test")) {
      byte[] payload = {1, 2, 3, 4, 5};
      provider.put("k", payload);

      Optional<byte[]> read = provider.get("k");

      assertThat(read).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3, 4, 5));
      assertThat(
              registry
                  .counter("vectorstore.cache.hit", "tier", "l2_offheap", "cache_name", "test")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Test
  void getReturnsAFreshCopyOfStoredBytes() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      provider.put("k", new byte[] {10, 20, 30});

      byte[] first = provider.get("k").orElseThrow();
      first[0] = 99;

      byte[] second = provider.get("k").orElseThrow();
      assertThat(second).containsExactly(10, 20, 30);
    }
  }

  @Test
  void missIncrementsMissCounter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, registry, "test")) {
      assertThat(provider.get("missing")).isEmpty();
      assertThat(
              registry
                  .counter("vectorstore.cache.miss", "tier", "l2_offheap", "cache_name", "test")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Test
  void invalidateRemovesEntryAndReclaimsBytes() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      provider.put("k", new byte[100]);
      assertThat(provider.stats().currentBytes()).isEqualTo(100L);

      provider.invalidate("k");

      assertThat(provider.get("k")).isEmpty();
      assertThat(provider.stats().currentBytes()).isZero();
      assertThat(provider.stats().currentEntries()).isZero();
    }
  }

  @Test
  void invalidateAllClearsEverything() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      provider.put("a", new byte[50]);
      provider.put("b", new byte[80]);
      provider.put("c", new byte[120]);

      provider.invalidateAll();

      assertThat(provider.get("a")).isEmpty();
      assertThat(provider.get("b")).isEmpty();
      assertThat(provider.get("c")).isEmpty();
      assertThat(provider.stats().currentBytes()).isZero();
      assertThat(provider.stats().currentEntries()).isZero();
    }
  }

  @Test
  void invalidateMatchingEvictsAcrossShards() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, registry, "test")) {
      for (int i = 0; i < 32; i++) {
        provider.put("alpha-" + i, new byte[64]);
        provider.put("beta-" + i, new byte[64]);
      }
      assertThat(provider.stats().currentEntries()).isEqualTo(64L);

      provider.invalidateMatching(s -> s.startsWith("alpha-"));

      assertThat(provider.stats().currentEntries()).isEqualTo(32L);
      for (int i = 0; i < 32; i++) {
        assertThat(provider.get("alpha-" + i)).isEmpty();
        assertThat(provider.get("beta-" + i)).isPresent();
      }
      assertThat(
              registry
                  .counter("vectorstore.cache.eviction", "tier", "l2_offheap", "cache_name", "test")
                  .count())
          .isZero();
    }
  }

  @Test
  void oversizedPutIsRejectedAtBlockSizeBoundary() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      byte[] tooBig = new byte[BLOCK_SIZE + 1];

      provider.put("k", tooBig);

      assertThat(provider.get("k")).isEmpty();
      assertThat(provider.stats().currentEntries()).isZero();
    }
  }

  @Test
  void evictionKicksInUnderByteSoftCapPressure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, registry, "test")) {
      // 200 distinct keys × 64 KiB = 12.8 MiB total → ~1.6 MiB per shard
      // average, comfortably above the 0.95 × 1 MiB = 998 KiB soft cap.
      int puts = 200;
      for (int i = 0; i < puts; i++) {
        provider.put("k-" + i, new byte[BLOCK_SIZE]);
      }

      assertThat(provider.stats().currentBytes()).isLessThanOrEqualTo(MAX_BYTES);
      double evictions =
          registry
              .counter("vectorstore.cache.eviction", "tier", "l2_offheap", "cache_name", "test")
              .count();
      assertThat(evictions)
          .as("byte-budget eviction must have ticked at least once")
          .isGreaterThan(0.0);
    }
  }

  @Test
  void evictionKicksInUnderSlotPoolPressureWithTrailingBlocks() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, registry, "test")) {
      // 1 KiB payloads occupy a full slot each. 128 total slots; after 128
      // puts every slot is taken with byte total 128 KiB — far under the
      // ~8 MiB byte cap. The 129th put must trigger eviction via the
      // slot-pool predicate, not the byte-budget predicate.
      int small = 1024;
      for (int i = 0; i < 200; i++) {
        provider.put("k-" + i, new byte[small]);
      }

      // Slot pool guaranteed eviction; byte total stays well under cap.
      assertThat(provider.stats().currentBytes()).isLessThanOrEqualTo(MAX_BYTES);
      double evictions =
          registry
              .counter("vectorstore.cache.eviction", "tier", "l2_offheap", "cache_name", "test")
              .count();
      assertThat(evictions)
          .as("slot-pool pressure must trigger eviction even when bytes are far under cap")
          .isGreaterThan(0.0);
    }
  }

  @Test
  void slotIsReusedAfterInvalidate() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, registry, "test")) {
      // Five small puts, well under any shard's soft cap.
      for (int i = 0; i < 5; i++) {
        provider.put("k-" + i, new byte[256]);
      }
      assertThat(provider.stats().currentEntries()).isEqualTo(5L);
      assertThat(provider.stats().currentBytes()).isEqualTo(5L * 256);

      provider.invalidate("k-2");
      assertThat(provider.stats().currentEntries()).isEqualTo(4L);
      assertThat(provider.stats().currentBytes()).isEqualTo(4L * 256);

      // The freed slot returns to its shard's free pool. A subsequent put
      // into the same shard reuses it; for a put that lands in a different
      // shard, a different free slot is taken — either way, no eviction
      // counter ticks because byte budget is far under the soft cap.
      provider.put("k-fresh", new byte[256]);
      assertThat(provider.stats().currentEntries()).isEqualTo(5L);
      assertThat(provider.stats().currentBytes()).isEqualTo(5L * 256);
      assertThat(
              registry
                  .counter("vectorstore.cache.eviction", "tier", "l2_offheap", "cache_name", "test")
                  .count())
          .as("no eviction expected — slot reuse + free pool drawn from")
          .isZero();
    }
  }

  @Test
  void trailingPayloadShorterThanBlockSizeIsStoredCorrectly() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      byte[] trailing = new byte[BLOCK_SIZE / 3];
      for (int i = 0; i < trailing.length; i++) {
        trailing[i] = (byte) (i & 0xFF);
      }
      provider.put("trail", trailing);

      Optional<byte[]> read = provider.get("trail");

      assertThat(read).isPresent();
      assertThat(read.get()).hasSize(trailing.length);
      assertThat(read.get()).containsExactly(trailing);
    }
  }

  @Test
  void overwriteUpdatesValueAndDoesNotInflateBytesOrSlots() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      provider.put("k", new byte[100]);
      provider.put("k", new byte[200]);

      assertThat(provider.get("k")).hasValueSatisfying(b -> assertThat(b).hasSize(200));
      assertThat(provider.stats().currentEntries()).isEqualTo(1L);
      assertThat(provider.stats().currentBytes()).isEqualTo(200L);
    }
  }

  @Test
  void useAfterCloseThrows() {
    SlabOffHeapL2Provider provider = new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test");
    provider.put("k", new byte[10]);
    provider.close();

    assertThatThrownBy(() -> provider.get("k")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.put("k", new byte[10]))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.invalidate("k")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.invalidateAll()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.invalidateMatching(s -> true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void closeIsIdempotent() {
    SlabOffHeapL2Provider provider = new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test");
    provider.close();
    provider.close();
  }

  @Test
  void zeroBudgetRejectsConstruction() {
    assertThatThrownBy(() -> new SlabOffHeapL2Provider(0L, BLOCK_SIZE, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBytes");
  }

  @Test
  void zeroBlockSizeRejectsConstruction() {
    assertThatThrownBy(() -> new SlabOffHeapL2Provider(MAX_BYTES, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blockSize");
  }

  @Test
  void tierNameIsL2Offheap() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      assertThat(provider.tierName()).isEqualTo(SlabOffHeapL2Provider.TIER_L2_OFFHEAP);
      assertThat(provider.tierName()).isEqualTo("l2_offheap");
    }
  }

  @Test
  void statsReflectByteAndEntryCounts() {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      provider.put("a", new byte[32]);
      provider.put("b", new byte[64]);

      CacheTierStats stats = provider.stats();
      assertThat(stats.currentBytes()).isEqualTo(96L);
      assertThat(stats.currentEntries()).isEqualTo(2L);
      assertThat(stats.maxBytes()).isEqualTo(MAX_BYTES);
    }
  }

  @Test
  void smallMaxBytesUsesSizedSegment() {
    // 1 MiB cache, 64 KiB block → 16 slots, 2 per shard. Smaller than
    // the default 16 MiB SEGMENT_BYTES, so segSize = maxBytes path is
    // exercised.
    long smallCap = 1L << 20;
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(smallCap, BLOCK_SIZE, null, "tiny")) {
      provider.put("k", new byte[BLOCK_SIZE]);
      assertThat(provider.get("k")).isPresent();
      assertThat(provider.stats().maxBytes()).isEqualTo(smallCap);
    }
  }

  @Test
  void maxBytesBelowEightShardsRejectsConstruction() {
    // Eight shards × 64 KiB block size = 512 KiB minimum.
    assertThatThrownBy(() -> new SlabOffHeapL2Provider(64 * 1024L, BLOCK_SIZE, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too small");
  }

  @Test
  void concurrentReadsAndWritesOnDifferentShardsDontInterfere() throws InterruptedException {
    try (SlabOffHeapL2Provider provider =
        new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test")) {
      // Pre-populate.
      for (int i = 0; i < 64; i++) {
        provider.put("k-" + i, new byte[256]);
      }

      int threads = 4;
      int iterations = 1_000;
      Thread[] workers = new Thread[threads];
      for (int t = 0; t < threads; t++) {
        final int tid = t;
        workers[t] =
            new Thread(
                () -> {
                  for (int i = 0; i < iterations; i++) {
                    String k = "k-" + ((tid * 16) + (i % 16));
                    provider.get(k);
                    if (i % 4 == 0) {
                      provider.put(k, new byte[256]);
                    }
                  }
                });
        workers[t].start();
      }
      for (Thread w : workers) {
        w.join();
      }
      assertThat(provider.stats().currentEntries()).isLessThanOrEqualTo(64L);
    }
  }

  @Test
  void arenaIsClosedDeterministically() {
    SlabOffHeapL2Provider provider = new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, null, "test");
    provider.put("k", new byte[BLOCK_SIZE]);

    provider.close(); // must not throw

    assertThatThrownBy(() -> provider.get("k")).isInstanceOf(IllegalStateException.class);
  }
}
