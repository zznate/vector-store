package io.github.zznate.vectorstore.core.cache.stress.scenarios;

import io.github.zznate.vectorstore.core.cache.stress.OpMix;
import io.github.zznate.vectorstore.core.cache.stress.ProviderKind;
import io.github.zznate.vectorstore.core.cache.stress.StressConfig;
import io.github.zznate.vectorstore.core.cache.stress.StressScenario;

/**
 * Eight threads racing on a four-key pool with a 30/60/10 op mix —
 * the put path is hot for every thread on every key, so per-key
 * compute serialisation in the harness is exercised continuously.
 * Tight mode: working-set bytes (4 keys × 4 KiB = 16 KiB) stay far
 * below {@code maxBytes / 2}.
 */
public final class SameKeyContentionScenario implements StressScenario {

  public static final String NAME = "same-key-contention";

  private static final int KEY_POOL_SIZE = 4;
  private static final int PAYLOAD_BYTES = 4 * 1024;
  private static final long MAX_BYTES = 16L << 20;
  private static final OpMix OP_MIX = new OpMix(30, 60, 10);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public StressConfig defaultConfig(ProviderKind kind, long seed) {
    return new StressConfig(
        NAME,
        /* threads= */ 8,
        /* opsPerThread= */ 5_000,
        KEY_POOL_SIZE,
        PAYLOAD_BYTES,
        PAYLOAD_BYTES,
        OP_MIX,
        StressConfig.Mode.TIGHT,
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
        KEY_POOL_SIZE,
        PAYLOAD_BYTES,
        PAYLOAD_BYTES,
        OP_MIX,
        StressConfig.Mode.TIGHT,
        seed,
        /* sampleEveryOps= */ 1_000,
        /* periodicInvalidateAllInterval= */ null);
  }

  @Override
  public long maxBytesFor(ProviderKind kind) {
    return MAX_BYTES;
  }
}
