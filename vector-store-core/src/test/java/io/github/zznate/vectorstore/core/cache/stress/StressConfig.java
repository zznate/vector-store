package io.github.zznate.vectorstore.core.cache.stress;

/**
 * Workload parameters for one run of {@link L2ProviderStressHarness}.
 * Threads × opsPerThread = total ops; each worker draws ops from the
 * configured op mix and key pool with a deterministic per-thread seed
 * ({@code seed + threadIndex}) so failures reproduce.
 *
 * <p>{@link Mode#TIGHT} runs assert oracle equality at end of run and
 * therefore require {@code keyPoolSize × payloadMax ≤ 50% × maxBytes}
 * so no eviction can happen. {@link Mode#EVICTION_AWARE} runs assert
 * only the relaxed invariants (bounded bytes, non-zero eviction
 * counter, no exceptions).
 */
public record StressConfig(
    String scenarioName,
    int threads,
    int opsPerThread,
    int keyPoolSize,
    int payloadMin,
    int payloadMax,
    OpMix opMix,
    Mode mode,
    long seed,
    int sampleEveryOps) {

  public StressConfig {
    if (threads <= 0) {
      throw new IllegalArgumentException("threads must be > 0, got " + threads);
    }
    if (opsPerThread <= 0) {
      throw new IllegalArgumentException("opsPerThread must be > 0, got " + opsPerThread);
    }
    if (keyPoolSize <= 0) {
      throw new IllegalArgumentException("keyPoolSize must be > 0, got " + keyPoolSize);
    }
    if (payloadMin <= 0 || payloadMax < payloadMin) {
      throw new IllegalArgumentException(
          "invalid payload range: [" + payloadMin + ", " + payloadMax + "]");
    }
    if (sampleEveryOps <= 0) {
      throw new IllegalArgumentException("sampleEveryOps must be > 0, got " + sampleEveryOps);
    }
  }

  public long totalOps() {
    return (long) threads * opsPerThread;
  }

  public enum Mode {
    TIGHT,
    EVICTION_AWARE
  }
}
