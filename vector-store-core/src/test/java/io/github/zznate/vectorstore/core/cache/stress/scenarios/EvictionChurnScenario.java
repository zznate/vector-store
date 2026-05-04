package io.github.zznate.vectorstore.core.cache.stress.scenarios;

import io.github.zznate.vectorstore.core.cache.stress.OpMix;
import io.github.zznate.vectorstore.core.cache.stress.ProviderKind;
import io.github.zznate.vectorstore.core.cache.stress.StressConfig;
import io.github.zznate.vectorstore.core.cache.stress.StressScenario;

/**
 * 0/100/0 (puts only) at provider-specific sizing that forces continuous
 * pre-emptive eviction. The slab tier sits at 1 MiB so a 128-key working
 * set blows past the per-shard slot pool repeatedly. LMDB sits at 16 MiB
 * so a 256-key working set lands on the soft cap without tripping the
 * copy-on-write transient pressure that would surface as
 * {@code MapFullException}. Eviction-aware mode: oracle equality is
 * relaxed; only bounded bytes, non-zero eviction counter, and absence of
 * worker exceptions are required.
 */
public final class EvictionChurnScenario implements StressScenario {

  public static final String NAME = "eviction-churn";

  private static final int PAYLOAD_BYTES = 64 * 1024;
  private static final long SLAB_MAX_BYTES = 1L << 20;
  private static final long LMDB_MAX_BYTES = 16L << 20;
  private static final long CHAIN_TIER_MAX_BYTES = 16L << 20;
  private static final OpMix OP_MIX = new OpMix(0, 100, 0);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public StressConfig defaultConfig(ProviderKind kind, long seed) {
    return new StressConfig(
        NAME,
        /* threads= */ 4,
        /* opsPerThread= */ 5_000,
        keyPoolFor(kind),
        PAYLOAD_BYTES,
        PAYLOAD_BYTES,
        OP_MIX,
        StressConfig.Mode.EVICTION_AWARE,
        seed,
        /* sampleEveryOps= */ 100,
        /* periodicInvalidateAllInterval= */ null);
  }

  @Override
  public StressConfig nightlyConfig(ProviderKind kind, long seed) {
    return new StressConfig(
        NAME,
        /* threads= */ 16,
        /* opsPerThread= */ 100_000,
        keyPoolFor(kind),
        PAYLOAD_BYTES,
        PAYLOAD_BYTES,
        OP_MIX,
        StressConfig.Mode.EVICTION_AWARE,
        seed,
        /* sampleEveryOps= */ 1_000,
        /* periodicInvalidateAllInterval= */ null);
  }

  @Override
  public long maxBytesFor(ProviderKind kind) {
    return switch (kind) {
      case SLAB -> SLAB_MAX_BYTES;
      case LMDB -> LMDB_MAX_BYTES;
      case CHAIN -> CHAIN_TIER_MAX_BYTES;
    };
  }

  private static int keyPoolFor(ProviderKind kind) {
    return switch (kind) {
      case SLAB -> 128;
      case LMDB, CHAIN -> 256;
    };
  }
}
