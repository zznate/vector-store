package io.github.zznate.vectorstore.core.cache.stress;

import java.time.Duration;
import java.util.List;

/**
 * Aggregated outcome of a {@link L2ProviderStressHarness#run} call —
 * op counts, hit/miss breakdown, byte high-watermarks, and any worker
 * exceptions captured by uncaught-exception handlers. The harness asserts
 * mode-specific invariants before returning, so a test that receives a
 * result is already past the failure path.
 */
public record StressRunResult(
    String scenarioName,
    Duration wallClock,
    long totalOps,
    long getCount,
    long putCount,
    long invalidateCount,
    long invalidateAllCount,
    long getHits,
    long getMisses,
    long peakBytes,
    long finalBytes,
    long evictionCount,
    List<Throwable> workerExceptions) {

  public StressRunResult {
    workerExceptions = List.copyOf(workerExceptions);
  }

  public boolean failed() {
    return !workerExceptions.isEmpty();
  }

  public double opsPerSecond() {
    long nanos = wallClock.toNanos();
    if (nanos == 0L) {
      return 0.0;
    }
    return (double) totalOps * 1_000_000_000L / nanos;
  }
}
