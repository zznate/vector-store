package io.github.zznate.vectorstore.core.cache.stress.scenarios;

import io.github.zznate.vectorstore.core.cache.stress.OpMix;
import io.github.zznate.vectorstore.core.cache.stress.ProviderKind;
import io.github.zznate.vectorstore.core.cache.stress.StressConfig;
import io.github.zznate.vectorstore.core.cache.stress.StressScenario;

/**
 * 90/9/1 (get/put/invalidate) at uniform sizing across providers.
 * Stresses the read path under a small key pool that gets repeatedly
 * touched, so puts and invalidates interleave with a hot read stream.
 * Tight mode: working-set bytes stay well under {@code maxBytes / 2}.
 */
public final class ReadHeavyScenario implements StressScenario {

  public static final String NAME = "read-heavy";

  private static final int KEY_POOL_SIZE = 64;
  private static final int PAYLOAD_BYTES = 4 * 1024;
  private static final long MAX_BYTES = 16L << 20;
  private static final OpMix OP_MIX = new OpMix(90, 9, 1);

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
