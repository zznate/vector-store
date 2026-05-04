package io.github.zznate.vectorstore.core.cache.stress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.github.zznate.vectorstore.core.cache.SlabOffHeapL2Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Finding #6 — per-shard contention. Read-heavy throughput at a
 * baseline thread count and a target thread count against the slab
 * provider; the test asserts that the target throughput is at least
 * {@value #THROUGHPUT_SCALING_MULTIPLIER_DEFAULT}× the baseline.
 * Plateau at the target count would mean the per-shard striping is no
 * longer unblocking parallel readers and the lock granularity needs
 * review.
 *
 * <p>Defaults compare 1 thread (no contention) → 4 threads (each shard
 * sees 1 thread on average) on an 8-shard slab. The 1t baseline is
 * stable enough to keep wall-clock noise from dominating the ratio,
 * which 4t→8t on hot fast paths can't guarantee on a laptop. Larger
 * machines can ramp up via system properties:
 * <ul>
 *   <li>{@code -Dl2.scaling.baseline.threads=N} — baseline (default 1)</li>
 *   <li>{@code -Dl2.scaling.target.threads=N} — target (default 4)</li>
 *   <li>{@code -Dl2.scaling.multiplier=R} — required ratio (default 1.5)</li>
 *   <li>{@code -Dl2.scaling.ops.per.thread=N} — ops/thread (default 100000)</li>
 *   <li>{@code -Dl2.scaling.curve.threads=1,4,8,16} — diagnostic curve thread set</li>
 * </ul>
 *
 * <p>The diagnostic-curve test walks both SLAB and LMDB across
 * {@link #CURVE_THREADS} to capture the throughput shape for the
 * harness README, but only SLAB is gated on the multiplier. LMDB
 * read scaling is dominated by its internal MVCC coordination
 * (mmap-page contention, shared reader-slot allocation in the env)
 * rather than the application-level shard lock, so the same target
 * doesn't apply cleanly — the bottleneck has moved off-shard.
 *
 * <p>Nightly only — the run pays for the warm-up of multiple cache
 * lifecycles per provider per kind, which is too heavy for the unit
 * suite. Chain is excluded because its write-through cost dominates
 * the per-shard signal that this test is measuring.
 */
@Tag("stress-nightly")
class L2ScalingTest {

  private static final double THROUGHPUT_SCALING_MULTIPLIER_DEFAULT = 1.5;

  private static final int BASELINE_THREADS =
      Integer.getInteger("l2.scaling.baseline.threads", 1);
  private static final int TARGET_THREADS =
      Integer.getInteger("l2.scaling.target.threads", 4);
  static final double THROUGHPUT_SCALING_MULTIPLIER =
      Double.parseDouble(
          System.getProperty(
              "l2.scaling.multiplier", Double.toString(THROUGHPUT_SCALING_MULTIPLIER_DEFAULT)));
  private static final int OPS_PER_THREAD =
      Integer.getInteger("l2.scaling.ops.per.thread", 100_000);
  private static final List<Integer> CURVE_THREADS = parseCurveThreads();

  private static final long MAX_BYTES = 16L << 20;
  private static final int BLOCK_SIZE = 64 * 1024;
  private static final int KEY_POOL = 64;
  private static final int PAYLOAD_BYTES = 4 * 1024;
  private static final long SEED = 5757L;
  private static final OpMix OP_MIX = new OpMix(90, 9, 1);

  private final L2ProviderStressHarness harness = new L2ProviderStressHarness();

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = ProviderKind.class, names = {"SLAB"})
  void readHeavyThroughputScalesAcrossThreads(ProviderKind kind) {
    double baseline = throughputAt(BASELINE_THREADS, kind);
    double target = throughputAt(TARGET_THREADS, kind);
    double ratio = target / baseline;
    assertThat(ratio)
        .withFailMessage(
            "%s read-heavy: %dt→%dt throughput ratio %.2fx fell below the "
                + "THROUGHPUT_SCALING_MULTIPLIER (%.2fx) — per-shard contention may have "
                + "plateaued. %dt=%.0f ops/s, %dt=%.0f ops/s",
            kind,
            BASELINE_THREADS,
            TARGET_THREADS,
            ratio,
            THROUGHPUT_SCALING_MULTIPLIER,
            BASELINE_THREADS,
            baseline,
            TARGET_THREADS,
            target)
        .isGreaterThanOrEqualTo(THROUGHPUT_SCALING_MULTIPLIER);
  }

  /** Walks {@link #CURVE_THREADS} and emits throughput numbers for diagnostic capture. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = ProviderKind.class, names = {"SLAB", "LMDB"})
  void readHeavyThroughputCurveProducesDiagnostic(ProviderKind kind) {
    CURVE_THREADS.forEach(t -> throughputAt(t, kind));
  }

  private static List<Integer> parseCurveThreads() {
    String spec = System.getProperty("l2.scaling.curve.threads", "1,4,8,16");
    return Arrays.stream(spec.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Integer::parseInt)
        .toList();
  }

  private double throughputAt(int threads, ProviderKind kind) {
    Path tempDir = mkTempDir("scaling-");
    L2Provider provider = newProvider(kind, tempDir);
    try {
      StressConfig cfg =
          new StressConfig(
              "scaling-" + threads + "t",
              threads,
              OPS_PER_THREAD,
              KEY_POOL,
              PAYLOAD_BYTES,
              PAYLOAD_BYTES,
              OP_MIX,
              StressConfig.Mode.TIGHT,
              SEED,
              /* sampleEveryOps= */ 1_000,
              /* periodicInvalidateAllInterval= */ null);
      StressRunResult result = harness.run(provider, cfg);
      return result.opsPerSecond();
    } finally {
      provider.close();
      deleteTree(tempDir);
    }
  }

  private static L2Provider newProvider(ProviderKind kind, Path tempDir) {
    return switch (kind) {
      case SLAB ->
          new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, new SimpleMeterRegistry(), "scaling");
      case LMDB ->
          new LmdbL2Provider(tempDir, MAX_BYTES, new SimpleMeterRegistry(), "scaling");
      case CHAIN ->
          throw new IllegalArgumentException("chain not measured by this test");
    };
  }

  private static Path mkTempDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteTree(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
