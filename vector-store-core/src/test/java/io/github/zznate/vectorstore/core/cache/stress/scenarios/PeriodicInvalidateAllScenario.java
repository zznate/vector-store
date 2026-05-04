package io.github.zznate.vectorstore.core.cache.stress.scenarios;

import io.github.zznate.vectorstore.core.cache.stress.OpMix;
import io.github.zznate.vectorstore.core.cache.stress.ProviderKind;
import io.github.zznate.vectorstore.core.cache.stress.StressConfig;
import io.github.zznate.vectorstore.core.cache.stress.StressScenario;
import java.time.Duration;

/**
 * Four worker threads at a 30/60/10 op mix plus an auxiliary thread
 * that calls {@code provider.invalidateAll()} every
 * {@value #INVALIDATOR_INTERVAL_MILLIS} ms under the harness's
 * write-lock. Exercises the bulk-clear path against concurrent per-key
 * mutations; tight mode requires the harness to keep oracle and
 * provider in sync across each invalidate-all boundary.
 */
public final class PeriodicInvalidateAllScenario implements StressScenario {

  public static final String NAME = "periodic-invalidate-all";

  private static final int KEY_POOL_SIZE = 64;
  private static final int PAYLOAD_BYTES = 4 * 1024;
  private static final long MAX_BYTES = 16L << 20;
  private static final OpMix OP_MIX = new OpMix(30, 60, 10);
  private static final long INVALIDATOR_INTERVAL_MILLIS = 50L;
  private static final Duration INVALIDATOR_INTERVAL =
      Duration.ofMillis(INVALIDATOR_INTERVAL_MILLIS);

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
        INVALIDATOR_INTERVAL);
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
        INVALIDATOR_INTERVAL);
  }

  @Override
  public long maxBytesFor(ProviderKind kind) {
    return MAX_BYTES;
  }
}
