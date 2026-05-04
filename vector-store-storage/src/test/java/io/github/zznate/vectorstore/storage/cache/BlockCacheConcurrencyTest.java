package io.github.zznate.vectorstore.storage.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Single-flight stress for {@link BlockCache#getIfPresent(BlockKey)}: 64
 * worker threads race 1000 random gets each across 8 pre-seeded keys
 * with a cold L1. Caffeine's loader collapse ({@code cache.get(key,
 * loader)}) should keep the loader-invocation total well under the
 * {@code threads × ops} ceiling — broken single-flight would let every
 * thread's miss spawn its own loader and the L1 miss counter would
 * climb past the per-key budget.
 *
 * <p>Pre-seeding goes directly to the L2 provider via
 * {@code lmdb.put(BlockCache.toL2Key(blockKey), bytes)} so L1 starts
 * empty and {@code BlockCache.getIfPresent} routes through
 * {@code tier.getOrLoad}; calling {@code cache.put} would warm L1 and
 * collapse the scenario to "every read is an L1 hit, loader never
 * runs". {@code BlockCache.toL2Key} is package-private and this test
 * lives in the same package so it can call it.
 *
 * <p>The single-flight bound is
 * {@code missCount &le; LOADER_INVOCATION_BUDGET_PER_KEY × KEY_COUNT}
 * (= 32). Caffeine guarantees one loader per key under contention, so
 * the expected total is roughly {@code KEY_COUNT}; the budget leaves
 * headroom for rare W-TinyLFU evict-then-readmit cycles. A loose
 * {@code &lt;&lt; 64 × 1000} bound would let a broken single-flight
 * gate (loader running per-thread) still pass.
 */
class BlockCacheConcurrencyTest {

  private static final int KEY_COUNT = 8;
  private static final int THREADS = 64;
  private static final int OPS_PER_THREAD = 1000;
  private static final int LOADER_INVOCATION_BUDGET_PER_KEY = 4;
  private static final long L2_MAX_BYTES = 16L << 20;
  private static final long L1_MAX_BYTES = 8L << 20;

  @Test
  void singleFlightCollapsesConcurrentMissesAcross64Threads(@TempDir Path tempDir) throws Exception {
    MeterRegistry registry = new SimpleMeterRegistry();
    try (LmdbL2Provider l2 = new LmdbL2Provider(tempDir, L2_MAX_BYTES, registry, BlockCache.CACHE_NAME)) {
      List<BlockKey> keys = seedL2(l2);
      BlockCache cache = new BlockCache(L1_MAX_BYTES, registry, l2);

      List<Throwable> exceptions = runConcurrentReads(cache, keys);

      assertThat(exceptions)
          .withFailMessage("worker exceptions: %s", exceptions)
          .isEmpty();

      long l1Misses = l1MissCount(registry);
      long l1MissBudget = (long) LOADER_INVOCATION_BUDGET_PER_KEY * KEY_COUNT;
      assertThat(l1Misses)
          .withFailMessage(
              "L1 miss count %d exceeds single-flight budget %d (per-key budget %d × %d keys)",
              l1Misses, l1MissBudget, LOADER_INVOCATION_BUDGET_PER_KEY, KEY_COUNT)
          .isLessThanOrEqualTo(l1MissBudget);

      assertThat(cache.tier().stats().currentEntries())
          .withFailMessage(
              "expected L1 to hold all %d keys after warm-up, got %d",
              KEY_COUNT, cache.tier().stats().currentEntries())
          .isGreaterThanOrEqualTo(KEY_COUNT);

      long l2Hits = l2HitCount(registry);
      assertThat(l2Hits)
          .withFailMessage(
              "L2 hit count %d should equal L1 miss count %d (each loader invocation reads L2 once)",
              l2Hits, l1Misses)
          .isEqualTo(l1Misses);
    }
  }

  private static List<BlockKey> seedL2(LmdbL2Provider l2) {
    List<BlockKey> keys = new ArrayList<>(KEY_COUNT);
    for (int i = 0; i < KEY_COUNT; i++) {
      BlockKey bk = new BlockKey("obj-" + i, /* blockIndex= */ 0L);
      byte[] payload = ("value-" + i).repeat(64).getBytes();
      l2.put(BlockCache.toL2Key(bk), payload);
      keys.add(bk);
    }
    return keys;
  }

  private static List<Throwable> runConcurrentReads(BlockCache cache, List<BlockKey> keys)
      throws InterruptedException {
    List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);

    Thread[] workers = new Thread[THREADS];
    Random masterRng = new Random(42L);
    long[] seeds = new long[THREADS];
    for (int i = 0; i < THREADS; i++) {
      seeds[i] = masterRng.nextLong();
    }

    for (int i = 0; i < THREADS; i++) {
      final int idx = i;
      workers[i] =
          new Thread(
              () -> runWorker(cache, keys, seeds[idx], ready, start, exceptions),
              "block-conc-" + i);
      workers[i].setUncaughtExceptionHandler((t, e) -> exceptions.add(e));
      workers[i].start();
    }
    ready.await();
    start.countDown();
    for (Thread t : workers) {
      t.join();
    }
    return exceptions;
  }

  private static void runWorker(
      BlockCache cache,
      List<BlockKey> keys,
      long seed,
      CountDownLatch ready,
      CountDownLatch start,
      List<Throwable> exceptions) {
    Random rng = new Random(seed);
    ready.countDown();
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    for (int j = 0; j < OPS_PER_THREAD; j++) {
      BlockKey bk = keys.get(rng.nextInt(KEY_COUNT));
      byte[] got = cache.getIfPresent(bk);
      if (got == null) {
        exceptions.add(new AssertionError("expected hit for " + bk + " (pre-seeded in L2)"));
        return;
      }
    }
  }

  private static long l1MissCount(MeterRegistry registry) {
    Tags tags =
        Tags.of(
            Tag.of(HeapCacheTier.TIER_TAG, HeapCacheTier.TIER_L1_HEAP),
            Tag.of(HeapCacheTier.CACHE_NAME_TAG, BlockCache.CACHE_NAME));
    return (long) registry.get(HeapCacheTier.METER_MISS).tags(tags).counter().count();
  }

  private static long l2HitCount(MeterRegistry registry) {
    Tags tags =
        Tags.of(
            Tag.of(HeapCacheTier.TIER_TAG, LmdbL2Provider.TIER_L2_DISK),
            Tag.of(HeapCacheTier.CACHE_NAME_TAG, BlockCache.CACHE_NAME));
    return (long) registry.get(HeapCacheTier.METER_HIT).tags(tags).counter().count();
  }
}
