package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LmdbL2ProviderTest {

  // 16 MiB total (2 MiB per shard, 1.5 MiB soft cap per shard). LMDB's
  // copy-on-write keeps pre-commit pages live in the env, so a write txn
  // briefly inflates the on-disk footprint above the steady-state user
  // bytes; sizing comfortably above the soft cap × shard count keeps the
  // tests deterministic under that transient pressure.
  private static final long MAX_BYTES = 16L << 20;

  @Test
  void putThenGetRoundTripsBytesAndIncrementsHit(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, registry, "test")) {
      byte[] payload = {1, 2, 3, 4, 5};
      provider.put("k", payload);

      Optional<byte[]> read = provider.get("k");

      assertThat(read).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3, 4, 5));
      assertThat(
              registry
                  .counter("vectorstore.cache.hit", "tier", "l2_disk", "cache_name", "test")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Test
  void getReturnsAFreshCopyOfStoredBytes(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("k", new byte[] {10, 20, 30});

      byte[] first = provider.get("k").orElseThrow();
      first[0] = 99;

      byte[] second = provider.get("k").orElseThrow();
      assertThat(second).containsExactly(10, 20, 30);
    }
  }

  @Test
  void missIncrementsMissCounter(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, registry, "test")) {
      assertThat(provider.get("missing")).isEmpty();
      assertThat(
              registry
                  .counter("vectorstore.cache.miss", "tier", "l2_disk", "cache_name", "test")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Test
  void invalidateRemovesEntryAndReclaimsBytes(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("k", new byte[100]);
      assertThat(provider.stats().currentBytes()).isEqualTo(100L);

      provider.invalidate("k");

      assertThat(provider.get("k")).isEmpty();
      assertThat(provider.stats().currentBytes()).isZero();
      assertThat(provider.stats().currentEntries()).isZero();
    }
  }

  @Test
  void invalidateAllClearsEverything(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
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
  void invalidateMatchingEvictsAcrossShards(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, registry, "test")) {
      // 32 keys distributed across 8 shards by hash — guarantees the
      // predicate hits multiple shards, exercising the all-shards-locked
      // sweep.
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
      // Explicit removal — eviction counter must not have ticked.
      assertThat(
              registry
                  .counter("vectorstore.cache.eviction", "tier", "l2_disk", "cache_name", "test")
                  .count())
          .isZero();
    }
  }

  @Test
  void overSizedPutThrowsIllegalArgumentException(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      byte[] huge = new byte[(int) (MAX_BYTES + 1)];

      assertThatThrownBy(() -> provider.put("k", huge))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("oversized");

      assertThat(provider.get("k")).isEmpty();
      assertThat(provider.stats().currentEntries()).isZero();
    }
  }

  @Test
  void evictionKicksInUnderSoftCapPressure(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, registry, "test")) {
      // Per-shard soft cap is 1.5 MiB. 256 puts of 64 KiB across 8 shards
      // averages 2 MiB per shard — above the cap, so every shard must
      // trigger pre-emptive eviction.
      int payload = 64 * 1024;
      int puts = 256;
      for (int i = 0; i < puts; i++) {
        provider.put("k-" + i, new byte[payload]);
      }

      assertThat(provider.stats().currentBytes()).isLessThanOrEqualTo(MAX_BYTES);
      double evictions =
          registry
              .counter("vectorstore.cache.eviction", "tier", "l2_disk", "cache_name", "test")
              .count();
      assertThat(evictions)
          .as("soft-cap pre-eviction must have ticked at least once")
          .isGreaterThan(0.0);
    }
  }

  @Test
  void mapFullExceptionDoesNotEscape(@TempDir Path tempDir) {
    // Aggressive fill: ~5× the byte budget worth of 64 KiB payloads. Soft
    // cap must absorb the pressure without the caller ever seeing a
    // MapFullException.
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      int payload = 64 * 1024;
      int puts = (int) (5 * MAX_BYTES / payload);
      for (int i = 0; i < puts; i++) {
        provider.put("k-" + i, new byte[payload]);
      }
      assertThat(provider.stats().currentBytes()).isLessThanOrEqualTo(MAX_BYTES);
    }
  }

  @Test
  void warmRestartPreservesKeySet(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      for (int i = 0; i < 16; i++) {
        provider.put("k-" + i, new byte[] {(byte) i});
      }
    }

    try (LmdbL2Provider reopened = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      assertThat(reopened.stats().currentEntries()).isEqualTo(16L);
      for (int i = 0; i < 16; i++) {
        Optional<byte[]> read = reopened.get("k-" + i);
        assertThat(read).hasValueSatisfying(b -> assertThat(b).hasSize(1));
        assertThat(read.get()[0]).isEqualTo((byte) i);
      }
    }
  }

  @Test
  void useAfterCloseThrows(@TempDir Path tempDir) {
    LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test");
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
  void closeIsIdempotent(@TempDir Path tempDir) {
    LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test");
    provider.close();
    // Second close must not throw.
    provider.close();
  }

  @Test
  void zeroBudgetRejectsConstruction(@TempDir Path tempDir) {
    assertThatThrownBy(() -> new LmdbL2Provider(tempDir, 0L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBytes");
  }

  @Test
  void legacyFilesArePreservedNotDeleted(@TempDir Path tempDir) throws IOException {
    Files.writeString(tempDir.resolve("data.bin"), "legacy");
    Files.writeString(tempDir.resolve("index.bin"), "legacy-index");

    // Provider must open cleanly with legacy siblings present.
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("k", new byte[] {1});
      assertThat(provider.get("k")).isPresent();
    }

    // Legacy files must survive — operator action only.
    assertThat(Files.exists(tempDir.resolve("data.bin"))).isTrue();
    assertThat(Files.exists(tempDir.resolve("index.bin"))).isTrue();
    assertThat(Files.readString(tempDir.resolve("data.bin"))).isEqualTo("legacy");
  }

  @Test
  void closeUnregistersMeters(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    int meterCountBefore = registry.getMeters().size();

    int cycles = 100;
    for (int i = 0; i < cycles; i++) {
      Path cycleDir = tempDir.resolve("cycle-" + i);
      try (LmdbL2Provider provider =
          new LmdbL2Provider(cycleDir, MAX_BYTES, registry, "test-" + i)) {
        provider.put("k", new byte[] {1});
      }
    }

    assertThat(registry.getMeters())
        .as("meters must return to baseline after close — distinct cache_name per cycle")
        .hasSize(meterCountBefore);
  }

  @Test
  void tierNameIsL2Disk(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      assertThat(provider.tierName()).isEqualTo(LmdbL2Provider.TIER_L2_DISK);
      assertThat(provider.tierName()).isEqualTo("l2_disk");
    }
  }

  @Test
  void statsReflectByteAndEntryCounts(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("a", new byte[32]);
      provider.put("b", new byte[64]);

      CacheTierStats stats = provider.stats();
      assertThat(stats.currentBytes()).isEqualTo(96L);
      assertThat(stats.currentEntries()).isEqualTo(2L);
      assertThat(stats.maxBytes()).isEqualTo(MAX_BYTES);
    }
  }

  @Test
  void overwriteUpdatesValueAndDoesNotInflateBytes(@TempDir Path tempDir) {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("k", new byte[100]);
      provider.put("k", new byte[200]);

      assertThat(provider.get("k")).hasValueSatisfying(b -> assertThat(b).hasSize(200));
      assertThat(provider.stats().currentEntries()).isEqualTo(1L);
      assertThat(provider.stats().currentBytes()).isEqualTo(200L);
    }
  }

  @Test
  void concurrentReadsScaleAcrossShards(@TempDir Path tempDir) throws InterruptedException {
    try (LmdbL2Provider provider = new LmdbL2Provider(tempDir, MAX_BYTES, null, "test")) {
      for (int i = 0; i < 64; i++) {
        provider.put("k-" + i, new byte[256]);
      }

      int threads = 4;
      int iterations = 1_000;
      Thread[] readers = new Thread[threads];
      for (int t = 0; t < threads; t++) {
        readers[t] =
            new Thread(
                () -> {
                  for (int i = 0; i < iterations; i++) {
                    provider.get("k-" + (i % 64));
                  }
                });
        readers[t].start();
      }
      for (Thread r : readers) {
        r.join();
      }
      // Smoke-test only: no assertion on throughput. The point is that
      // concurrent reads on disjoint shards complete without exception.
      assertThat(provider.stats().currentEntries()).isEqualTo(64L);
    }
  }
}
