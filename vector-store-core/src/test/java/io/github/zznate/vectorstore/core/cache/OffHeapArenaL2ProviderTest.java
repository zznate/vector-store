package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OffHeapArenaL2ProviderTest {

  private SimpleMeterRegistry registry;
  private OffHeapArenaL2Provider provider;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    provider = new OffHeapArenaL2Provider(1L << 20, registry, "test");
  }

  @AfterEach
  void tearDown() {
    provider.close();
  }

  @Test
  void putThenGetRoundTripsBytesAndIncrementsHit() {
    byte[] payload = {1, 2, 3, 4, 5};
    provider.put("k", payload);

    Optional<byte[]> read = provider.get("k");

    assertThat(read).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3, 4, 5));
    assertThat(registry.counter(
            "vectorstore.cache.hit", "tier", "l2_offheap", "cache_name", "test").count())
        .isEqualTo(1.0);
  }

  @Test
  void getReturnsAFreshCopyOfTheStoredBytes() {
    byte[] payload = {10, 20, 30};
    provider.put("k", payload);

    byte[] first = provider.get("k").orElseThrow();
    byte[] second = provider.get("k").orElseThrow();

    assertThat(first).isNotSameAs(second);
    assertThat(first).isEqualTo(second);

    // Mutating the returned byte[] doesn't poison the cache.
    first[0] = 99;
    byte[] third = provider.get("k").orElseThrow();
    assertThat(third).containsExactly(10, 20, 30);
  }

  @Test
  void missIncrementsMissCounter() {
    Optional<byte[]> result = provider.get("absent");

    assertThat(result).isEmpty();
    assertThat(registry.counter(
            "vectorstore.cache.miss", "tier", "l2_offheap", "cache_name", "test").count())
        .isEqualTo(1.0);
  }

  @Test
  void overBudgetPutEvictsLRUEntry() {
    // 200-byte budget; three 100-byte entries forces one eviction.
    OffHeapArenaL2Provider tiny = new OffHeapArenaL2Provider(200L, registry, "tight");
    try {
      tiny.put("a", new byte[100]);
      tiny.put("b", new byte[100]);
      tiny.put("c", new byte[100]);

      assertThat(tiny.stats().currentEntries()).isLessThanOrEqualTo(2L);
      assertThat(tiny.stats().currentBytes()).isLessThanOrEqualTo(200L);
      assertThat(tiny.stats().evictionCount()).isGreaterThanOrEqualTo(1L);
      // 'a' was the LRU entry; it should be the one evicted.
      assertThat(tiny.get("a")).isEmpty();
      assertThat(tiny.get("b")).isPresent();
      assertThat(tiny.get("c")).isPresent();
    } finally {
      tiny.close();
    }
  }

  @Test
  void getRefreshesLRUOrder() {
    OffHeapArenaL2Provider tiny = new OffHeapArenaL2Provider(200L, registry, "lru");
    try {
      tiny.put("a", new byte[100]);
      tiny.put("b", new byte[100]);
      tiny.get("a"); // refresh 'a' so 'b' becomes the LRU.
      tiny.put("c", new byte[100]); // evicts 'b'.

      assertThat(tiny.get("a")).isPresent();
      assertThat(tiny.get("b")).isEmpty();
      assertThat(tiny.get("c")).isPresent();
    } finally {
      tiny.close();
    }
  }

  @Test
  void overBudgetSinglePutIsRejectedWithoutEvictingExistingEntries() {
    OffHeapArenaL2Provider tiny = new OffHeapArenaL2Provider(100L, registry, "reject");
    try {
      tiny.put("kept", new byte[50]);
      tiny.put("oversized", new byte[200]); // > maxBytes

      assertThat(tiny.get("kept")).isPresent();
      assertThat(tiny.get("oversized")).isEmpty();
      assertThat(tiny.stats().currentEntries()).isEqualTo(1L);
    } finally {
      tiny.close();
    }
  }

  @Test
  void putReplacesExistingEntryAndUpdatesByteAccounting() {
    provider.put("k", new byte[100]);
    assertThat(provider.stats().currentBytes()).isEqualTo(100L);

    provider.put("k", new byte[300]);

    assertThat(provider.stats().currentEntries()).isEqualTo(1L);
    assertThat(provider.stats().currentBytes()).isEqualTo(300L);
    assertThat(provider.get("k").orElseThrow()).hasSize(300);
  }

  @Test
  void invalidateRemovesEntryAndUpdatesBytes() {
    provider.put("a", new byte[100]);
    provider.put("b", new byte[200]);

    provider.invalidate("a");

    assertThat(provider.get("a")).isEmpty();
    assertThat(provider.stats().currentEntries()).isEqualTo(1L);
    assertThat(provider.stats().currentBytes()).isEqualTo(200L);
  }

  @Test
  void invalidateMatchingEvictsPredicateMatches() {
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
                .counter("vectorstore.cache.eviction", "tier", "l2_offheap", "cache_name", "test")
                .count())
        .isZero();
  }

  @Test
  void invalidateAllClearsEverything() {
    provider.put("a", new byte[10]);
    provider.put("b", new byte[20]);
    provider.put("c", new byte[30]);

    provider.invalidateAll();

    assertThat(provider.stats().currentEntries()).isZero();
    assertThat(provider.stats().currentBytes()).isZero();
    assertThat(provider.get("a")).isEmpty();
  }

  @Test
  void closeReleasesEverything() {
    provider.put("a", new byte[100]);
    provider.put("b", new byte[100]);

    provider.close();

    assertThat(provider.stats().currentEntries()).isZero();
    assertThat(provider.stats().currentBytes()).isZero();
  }

  @Test
  void zeroBudgetRejectsConstruction() {
    assertThatThrownBy(() -> new OffHeapArenaL2Provider(0L, registry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBytes");
  }

  @Test
  void stressArenaLifecyclePutsAndEvictsThousandsOfEntriesWithoutLeak() {
    // Tight budget so most puts trigger eviction. We assert that bytes
    // tracking stays bounded — every Arena from an evicted entry must
    // have been closed by the provider, otherwise currentBytes would
    // skew above the budget.
    OffHeapArenaL2Provider stressing = new OffHeapArenaL2Provider(64 * 1024L, registry, "stress");
    try {
      ThreadLocalRandom rng = ThreadLocalRandom.current();
      int iterations = 5_000;
      int payloadSize = 256;
      for (int i = 0; i < iterations; i++) {
        byte[] payload = new byte[payloadSize];
        rng.nextBytes(payload);
        stressing.put("k-" + i, payload);
        assertThat(stressing.stats().currentBytes()).isLessThanOrEqualTo(64 * 1024L);
      }
      // Drain the rest.
      stressing.invalidateAll();
      assertThat(stressing.stats().currentBytes()).isZero();
      assertThat(stressing.stats().currentEntries()).isZero();
    } finally {
      stressing.close();
    }
  }

  @Test
  void tierNameIsL2Offheap() {
    assertThat(provider.tierName()).isEqualTo(OffHeapArenaL2Provider.TIER_L2_OFFHEAP);
  }
}
