package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChainedL2ProviderTest {

  @Test
  void constructorRejectsEmptyProviderList() {
    assertThatThrownBy(() -> new ChainedL2Provider(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one provider");
  }

  @Test
  void putWritesThroughToEveryTier() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    chain.put("k", new byte[]{1, 2, 3});

    assertThat(upper.lastValueOf("k")).containsExactly(1, 2, 3);
    assertThat(lower.lastValueOf("k")).containsExactly(1, 2, 3);
  }

  @Test
  void getReturnsImmediatelyOnUpperHitWithoutTouchingLower() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    upper.put("k", new byte[]{9});
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    Optional<byte[]> hit = chain.get("k");

    assertThat(hit).hasValueSatisfying(b -> assertThat(b).containsExactly(9));
    assertThat(lower.getCalls.get()).isZero();
  }

  @Test
  void lowerTierHitPromotesToUpperTiersOnly() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    lower.put("k", new byte[]{42});
    upper.putCalls.set(0); // ignore the seed put
    lower.putCalls.set(0);
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    Optional<byte[]> hit = chain.get("k");

    assertThat(hit).hasValueSatisfying(b -> assertThat(b).containsExactly(42));
    // Upper got promoted; lower was the source so should not be re-put.
    assertThat(upper.lastValueOf("k")).containsExactly(42);
    assertThat(upper.putCalls.get()).isEqualTo(1);
    assertThat(lower.putCalls.get()).isZero();
  }

  @Test
  void allMissReturnsEmpty() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    assertThat(chain.get("missing")).isEmpty();
  }

  @Test
  void invalidateCascadesToEveryTier() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    upper.put("k", new byte[]{1});
    lower.put("k", new byte[]{1});
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    chain.invalidate("k");

    assertThat(upper.lastValueOf("k")).isNull();
    assertThat(lower.lastValueOf("k")).isNull();
  }

  @Test
  void invalidateAllCascadesToEveryTier() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    upper.put("a", new byte[]{1});
    lower.put("b", new byte[]{2});
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    chain.invalidateAll();

    assertThat(upper.invalidateAllCalls.get()).isEqualTo(1);
    assertThat(lower.invalidateAllCalls.get()).isEqualTo(1);
    assertThat(upper.lastValueOf("a")).isNull();
    assertThat(lower.lastValueOf("b")).isNull();
  }

  @Test
  void statsAggregatesAcrossEveryTier() {
    RecordingProvider upper = new RecordingProvider("upper", new CacheTierStats(10, 5, 2, 100, 1024, 4));
    RecordingProvider lower = new RecordingProvider("lower", new CacheTierStats(3, 7, 1, 200, 4096, 6));
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    CacheTierStats agg = chain.stats();

    assertThat(agg.hitCount()).isEqualTo(13);
    assertThat(agg.missCount()).isEqualTo(12);
    assertThat(agg.evictionCount()).isEqualTo(3);
    assertThat(agg.currentBytes()).isEqualTo(300);
    assertThat(agg.maxBytes()).isEqualTo(5120);
    assertThat(agg.currentEntries()).isEqualTo(10);
  }

  @Test
  void closeCascadesToEveryTier() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    chain.close();

    assertThat(upper.closeCalls.get()).isEqualTo(1);
    assertThat(lower.closeCalls.get()).isEqualTo(1);
  }

  @Test
  void closeContinuesAfterOneTierThrows() {
    RecordingProvider upper =
        new RecordingProvider("upper") {
          @Override
          public void close() {
            closeCalls.incrementAndGet();
            throw new RuntimeException("boom");
          }
        };
    RecordingProvider lower = new RecordingProvider("lower");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    chain.close(); // must not throw

    assertThat(upper.closeCalls.get()).isEqualTo(1);
    assertThat(lower.closeCalls.get()).isEqualTo(1);
  }

  @Test
  void singleTierChainBehavesAsPassthrough() {
    RecordingProvider only = new RecordingProvider("only");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(only));

    chain.put("k", new byte[]{7});
    assertThat(chain.get("k")).hasValueSatisfying(b -> assertThat(b).containsExactly(7));

    chain.invalidate("k");
    assertThat(chain.get("k")).isEmpty();
  }

  @Test
  void tierNameIsChained() {
    RecordingProvider only = new RecordingProvider("only");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(only));

    assertThat(chain.tierName()).isEqualTo("chained");
  }

  @Test
  void providersAccessorPreservesOrder() {
    RecordingProvider upper = new RecordingProvider("upper");
    RecordingProvider lower = new RecordingProvider("lower");
    ChainedL2Provider chain = new ChainedL2Provider(List.of(upper, lower));

    assertThat(chain.providers()).extracting(L2Provider::tierName).containsExactly("upper", "lower");
  }

  /**
   * In-memory {@link L2Provider} that records each call and stores
   * payloads. Subclassed in one test to inject a close-time failure;
   * fields are package-private so the subclass can update them.
   */
  private static class RecordingProvider implements L2Provider {

    final String tierName;
    final Map<String, byte[]> store = new HashMap<>();
    final AtomicInteger getCalls = new AtomicInteger();
    final AtomicInteger putCalls = new AtomicInteger();
    final AtomicInteger invalidateAllCalls = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    private final CacheTierStats stubStats;

    RecordingProvider(String tierName) {
      this(tierName, new CacheTierStats(0, 0, 0, 0, 0, 0));
    }

    RecordingProvider(String tierName, CacheTierStats stubStats) {
      this.tierName = tierName;
      this.stubStats = stubStats;
    }

    @Override
    public Optional<byte[]> get(String key) {
      getCalls.incrementAndGet();
      return Optional.ofNullable(store.get(key));
    }

    @Override
    public void put(String key, byte[] bytes) {
      putCalls.incrementAndGet();
      store.put(key, bytes);
    }

    @Override
    public void invalidate(String key) {
      store.remove(key);
    }

    @Override
    public void invalidateAll() {
      invalidateAllCalls.incrementAndGet();
      store.clear();
    }

    @Override
    public CacheTierStats stats() {
      return stubStats;
    }

    @Override
    public String tierName() {
      return tierName;
    }

    @Override
    public void close() {
      closeCalls.incrementAndGet();
    }

    byte[] lastValueOf(String key) {
      return store.get(key);
    }
  }
}
