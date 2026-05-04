package io.github.zznate.vectorstore.core.cache.stress;

/**
 * One named workload pattern (read-heavy, write-heavy, eviction-churn,
 * etc.) and the per-provider sizing it requires. Implementations live
 * under {@code …stress.scenarios.*}, one file each, so PMD complexity
 * metrics stay per-class.
 *
 * <p>Workload params and sizing both vary by {@link ProviderKind}:
 * eviction-churn for example sets a small {@code maxBytes} on the slab
 * tier (1 MiB) but a larger one on LMDB (16 MiB) to absorb LMDB's
 * copy-on-write transient pressure. Read-heavy and write-heavy use
 * uniform sizing across kinds.
 */
public interface StressScenario {

  String name();

  /** Workload at default intensity (runs in the unit suite). */
  StressConfig defaultConfig(ProviderKind kind, long seed);

  /** Workload at nightly intensity (gated by the {@code stress-nightly} profile). */
  StressConfig nightlyConfig(ProviderKind kind, long seed);

  /** {@code maxBytes} for the given provider kind under this scenario. */
  long maxBytesFor(ProviderKind kind);

  /** {@code blockSize} for the slab provider under this scenario. */
  default int slabBlockSize() {
    return 64 * 1024;
  }
}
