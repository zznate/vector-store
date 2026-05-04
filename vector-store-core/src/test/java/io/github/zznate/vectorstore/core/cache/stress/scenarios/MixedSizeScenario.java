package io.github.zznate.vectorstore.core.cache.stress.scenarios;

import io.github.zznate.vectorstore.core.cache.stress.OpMix;
import io.github.zznate.vectorstore.core.cache.stress.ProviderKind;
import io.github.zznate.vectorstore.core.cache.stress.StressConfig;
import io.github.zznate.vectorstore.core.cache.stress.StressScenario;

/**
 * Four threads at a 30/60/10 op mix with payloads sampled uniformly
 * from {@code [1, 64 KiB]}. Stresses the slab tier's per-entry length
 * field (each slot is full-blockSize but {@code currentBytes} tracks
 * actual payload length) and LMDB's variable-length value paths.
 * Tight mode: working-set peak (64 keys × 64 KiB = 4 MiB) fits below
 * {@code maxBytes / 2 = 8 MiB}.
 */
public final class MixedSizeScenario implements StressScenario {

  public static final String NAME = "mixed-size";

  private static final int KEY_POOL_SIZE = 64;
  private static final int PAYLOAD_MIN = 1;
  private static final int PAYLOAD_MAX = 64 * 1024;
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
        /* threads= */ 4,
        /* opsPerThread= */ 5_000,
        KEY_POOL_SIZE,
        PAYLOAD_MIN,
        PAYLOAD_MAX,
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
        PAYLOAD_MIN,
        PAYLOAD_MAX,
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
