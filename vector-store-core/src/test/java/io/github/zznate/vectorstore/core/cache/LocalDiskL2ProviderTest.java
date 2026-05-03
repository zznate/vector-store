package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskL2ProviderTest {

  private static final long MAX_BYTES = 1L << 14; // 16 KiB — small to force eviction

  @Test
  void putThenGetRoundTripsBytesAndIncrementsHit(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, registry, "test")) {
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
  void getReturnsAFreshCopyOfTheStoredBytes(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      byte[] payload = {10, 20, 30};
      provider.put("k", payload);

      byte[] first = provider.get("k").orElseThrow();
      byte[] second = provider.get("k").orElseThrow();

      assertThat(first).isNotSameAs(second);
      assertThat(first).containsExactly(payload);
      assertThat(second).containsExactly(payload);

      // Mutating the read does not affect storage.
      first[0] = 99;
      assertThat(provider.get("k").orElseThrow()).containsExactly(payload);
    }
  }

  @Test
  void getOnMissingKeyReturnsEmptyAndIncrementsMiss(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, registry, "test")) {
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
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("k", new byte[100]);
      assertThat(provider.stats().currentBytes()).isEqualTo(100);

      provider.invalidate("k");

      assertThat(provider.get("k")).isEmpty();
      assertThat(provider.stats().currentBytes()).isZero();
      assertThat(provider.stats().currentEntries()).isZero();
    }
  }

  @Test
  void invalidateAllClearsEverything(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("a", new byte[50]);
      provider.put("b", new byte[80]);

      provider.invalidateAll();

      assertThat(provider.get("a")).isEmpty();
      assertThat(provider.get("b")).isEmpty();
      assertThat(provider.stats().currentBytes()).isZero();
    }
  }

  @Test
  void invalidateMatchingEvictsPredicateMatches(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, registry, "test")) {
      provider.put("a-1", new byte[100]);
      provider.put("a-2", new byte[100]);
      provider.put("b-1", new byte[200]);

      provider.invalidateMatching(s -> s.startsWith("a-"));

      assertThat(provider.get("a-1")).isEmpty();
      assertThat(provider.get("a-2")).isEmpty();
      assertThat(provider.get("b-1")).hasValueSatisfying(b -> assertThat(b).hasSize(200));
      assertThat(provider.stats().currentBytes()).isEqualTo(200L);
      assertThat(provider.stats().currentEntries()).isEqualTo(1L);
      assertThat(
              registry
                  .counter("vectorstore.cache.eviction", "tier", "l2_disk", "cache_name", "test")
                  .count())
          .isZero();
    }
  }

  @Test
  void invalidateMatchingReleasesSlotsToFreeListForReuse(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("a-1", new byte[100]);
      provider.put("a-2", new byte[100]);

      provider.invalidateMatching(s -> s.startsWith("a-"));

      // Reused slots: a same-sized put should fit even after another byte
      // budget is filled; relies on releaseToFreeList being called.
      provider.put("c", new byte[100]);
      provider.put("d", new byte[100]);
      assertThat(provider.get("c")).isPresent();
      assertThat(provider.get("d")).isPresent();
    }
  }

  @Test
  void overSizedPutIsRejectedSilently(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, 1024L, null, "test")) {
      byte[] huge = new byte[2048];
      provider.put("too-big", huge);

      assertThat(provider.get("too-big")).isEmpty();
      assertThat(provider.stats().currentBytes()).isZero();
    }
  }

  @Test
  void putBeyondBudgetWrapsAndEvictsOverlappingEntries(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, 4096L, registry, "test")) {
      // Fill the cache, then write enough that the bump pointer wraps
      // back over the early entries.
      provider.put("a", new byte[2048]); // [0, 2048)
      provider.put("b", new byte[2048]); // [2048, 4096)
      assertThat(provider.get("a")).isPresent();
      assertThat(provider.get("b")).isPresent();

      // Next put forces wrap; allocOffset goes back to 0 and "a" is
      // overwritten.
      provider.put("c", new byte[2048]); // wraps to [0, 2048), evicts "a"

      assertThat(provider.get("a")).isEmpty();
      assertThat(provider.get("b")).isPresent();
      assertThat(provider.get("c")).isPresent();
      assertThat(
              registry
                  .counter("vectorstore.cache.eviction", "tier", "l2_disk", "cache_name", "test")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Test
  void invalidatedSlotIsReusedBySameSizePut(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, 4096L, null, "test")) {
      provider.put("a", new byte[1024]);
      provider.put("b", new byte[1024]);
      provider.invalidate("a");

      // The next 1024-byte put must reuse "a"'s slot rather than
      // bump-allocating, otherwise we'd waste space.
      provider.put("c", new byte[1024]);

      // Both b and c are present; total bytes == 2048, not 3072.
      assertThat(provider.get("b")).isPresent();
      assertThat(provider.get("c")).isPresent();
      assertThat(provider.stats().currentBytes()).isEqualTo(2048L);
    }
  }

  @Test
  void cleanShutdownPersistsIndexAndWarmRestartReadsIt(@TempDir Path tempDir) {
    SimpleMeterRegistry firstRegistry = new SimpleMeterRegistry();
    LocalDiskL2Provider first =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, firstRegistry, "test");
    first.put("k1", "hello".getBytes(StandardCharsets.UTF_8));
    first.put("k2", "world".getBytes(StandardCharsets.UTF_8));
    first.close();

    SimpleMeterRegistry secondRegistry = new SimpleMeterRegistry();
    try (LocalDiskL2Provider second =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, secondRegistry, "test")) {
      assertThat(second.get("k1"))
          .hasValueSatisfying(b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("hello"));
      assertThat(second.get("k2"))
          .hasValueSatisfying(b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("world"));
    }
  }

  @Test
  void corruptIndexFileFallsBackToColdCacheWithoutCrashing(@TempDir Path tempDir) throws IOException {
    LocalDiskL2Provider first = new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test");
    first.put("k", "data".getBytes(StandardCharsets.UTF_8));
    first.close();

    // Corrupt the index sidecar — keep the data file intact.
    Path indexFile = tempDir.resolve(LocalDiskL2Provider.INDEX_FILE_NAME);
    Files.write(indexFile, new byte[]{0, 0, 0, 0, 0, 0, 0, 0});

    try (LocalDiskL2Provider restarted =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      // Expected behaviour: cold start, key gone, no crash.
      assertThat(restarted.get("k")).isEmpty();
      assertThat(restarted.stats().currentBytes()).isZero();
    }
  }

  @Test
  void missingIndexFileWithDataFilePresentColdStartsClean(@TempDir Path tempDir) throws IOException {
    LocalDiskL2Provider first = new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test");
    first.put("k", "data".getBytes(StandardCharsets.UTF_8));
    first.close();

    Path indexFile = tempDir.resolve(LocalDiskL2Provider.INDEX_FILE_NAME);
    Files.delete(indexFile);

    try (LocalDiskL2Provider restarted =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      assertThat(restarted.get("k")).isEmpty();
    }
  }

  @Test
  void secondProviderOnSameDirectoryThrowsLockError(@TempDir Path tempDir) {
    try (LocalDiskL2Provider first = new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      assertThatThrownBy(() -> new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test"))
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("L2 disk cache");
    }
  }

  @Test
  void zeroOrNegativeMaxBytesIsRejected(@TempDir Path tempDir) {
    assertThatThrownBy(() -> new LocalDiskL2Provider(tempDir, 0L, null, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LocalDiskL2Provider(tempDir, -1L, null, "test"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overwritingExistingKeyKeepsByteAccountingCorrect(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      provider.put("k", new byte[100]);
      provider.put("k", new byte[200]); // overwrite

      assertThat(provider.stats().currentBytes()).isEqualTo(200);
      assertThat(provider.stats().currentEntries()).isEqualTo(1);
      assertThat(provider.get("k").orElseThrow()).hasSize(200);
    }
  }

  @Test
  void tierNameIsL2Disk(@TempDir Path tempDir) {
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, MAX_BYTES, null, "test")) {
      assertThat(provider.tierName()).isEqualTo(LocalDiskL2Provider.TIER_L2_DISK);
    }
  }

  /**
   * Regression for the {@code MappedByteBuffer} {@code int}-indexing
   * limit: the JDK 21 {@code FileChannel.map(MapMode, long, long, Arena)}
   * overload returns a long-indexed {@link
   * java.lang.foreign.MemorySegment}, so we can configure {@code maxBytes}
   * past 2 GiB without {@code IllegalArgumentException} at construction.
   * The underlying file is sparse on macOS / Linux / Windows-NTFS, so
   * this test does not actually allocate 3 GiB on disk.
   */
  @Test
  void supportsMaxBytesAbove2GiB(@TempDir Path tempDir) {
    long threeGiB = 3L * 1024 * 1024 * 1024;
    try (LocalDiskL2Provider provider =
        new LocalDiskL2Provider(tempDir, threeGiB, null, "test")) {
      // Write enough to push the bump pointer past the int-max boundary.
      byte[] payload = new byte[1024];
      for (int i = 0; i < payload.length; i++) {
        payload[i] = (byte) (i & 0xff);
      }
      provider.put("k1", payload);
      assertThat(provider.get("k1").orElseThrow()).containsExactly(payload);
      assertThat(provider.stats().maxBytes()).isEqualTo(threeGiB);
    }
  }
}
