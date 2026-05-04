package io.github.zznate.vectorstore.core.cache.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.zznate.vectorstore.core.cache.ChainedL2Provider;
import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.github.zznate.vectorstore.core.cache.SlabOffHeapL2Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Long-running soak test for the L2 stack. Each test method runs a
 * mixed workload against one provider for the configured duration
 * (default 30 minutes; override with {@code -Dl2.soak.duration=PT5M}),
 * sampling cache-tier and JVM heap metrics at one-minute granularity
 * and recording {@code jdk.NativeMemoryUsage} via JFR. At end of run
 * the minute-5 anchor (post-warm-up baseline) is compared against the
 * end-of-run anchor; the delta must stay within
 * {@value #NOISE_BAND_PERCENT}% (Finding #21).
 *
 * <p>Required JVM flags ({@code -Psoak} adds these to the test
 * argLine):
 * <ul>
 *   <li>{@code -XX:NativeMemoryTracking=summary} — required for JFR's
 *       {@code jdk.NativeMemoryUsage} event to fire.
 * </ul>
 *
 * <p>The minute-5 anchor compare requires at least six minutes of
 * sampling; shorter overrides skip the anchor assertion (the run still
 * exercises the workload and dumps JFR for inspection).
 */
@Tag("soak")
class L2ProviderSoakTest {

  private static final Duration DEFAULT_DURATION = Duration.ofMinutes(30);
  private static final Duration SAMPLE_INTERVAL = Duration.ofMinutes(1);
  private static final int BASELINE_SAMPLE_INDEX = 5;
  private static final double NOISE_BAND = 0.05;
  private static final int NOISE_BAND_PERCENT = 5;

  private static final long MAX_BYTES = 64L << 20;
  private static final int BLOCK_SIZE = 64 * 1024;
  private static final int KEY_POOL = 4_096;
  private static final int PAYLOAD_BYTES = 4 * 1024;
  private static final int WORKER_THREADS = 4;
  private static final Duration INVALIDATE_ALL_INTERVAL = Duration.ofSeconds(30);

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProviderKind.class)
  void steadyStateHasBoundedNativeMemoryGrowth(ProviderKind kind, @TempDir Path tempDir)
      throws Exception {
    Duration duration = parseDuration();
    L2Provider provider = newProvider(kind, tempDir);
    Path jfrPath = tempDir.resolve("soak-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".jfr");

    List<Sample> samples;
    try (Recording recording = new Recording()) {
      recording.enable("jdk.NativeMemoryUsage").withPeriod(Duration.ofSeconds(60));
      recording.setDestination(jfrPath);
      recording.start();

      samples = runSoak(provider, duration);

      recording.stop();
    } finally {
      provider.close();
    }

    assertThat(jfrPath)
        .withFailMessage("expected JFR recording dumped at %s", jfrPath)
        .exists();
    verifyAnchorDelta(samples);
  }

  private List<Sample> runSoak(L2Provider provider, Duration duration) throws InterruptedException {
    AtomicBoolean stop = new AtomicBoolean(false);
    List<Sample> samples = new CopyOnWriteArrayList<>();

    Thread sampler = startSampler(provider, samples, stop);
    Thread invalidator = startInvalidator(provider, stop);
    Thread[] workers = startWorkers(provider, stop);

    Thread.sleep(duration.toMillis());

    stop.set(true);
    invalidator.interrupt();
    sampler.interrupt();
    for (Thread w : workers) {
      w.join();
    }
    invalidator.join();
    sampler.join();

    return List.copyOf(samples);
  }

  private static Thread startSampler(L2Provider provider, List<Sample> samples, AtomicBoolean stop) {
    Thread t =
        new Thread(
            () -> {
              MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
              while (!stop.get()) {
                try {
                  Thread.sleep(SAMPLE_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                samples.add(snapshot(provider, memBean));
              }
            },
            "soak-sampler");
    t.setDaemon(true);
    t.start();
    return t;
  }

  private static Thread startInvalidator(L2Provider provider, AtomicBoolean stop) {
    Thread t =
        new Thread(
            () -> {
              while (!stop.get()) {
                try {
                  Thread.sleep(INVALIDATE_ALL_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (!stop.get()) {
                  provider.invalidateAll();
                }
              }
            },
            "soak-invalidator");
    t.setDaemon(true);
    t.start();
    return t;
  }

  private static Thread[] startWorkers(L2Provider provider, AtomicBoolean stop) {
    Thread[] workers = new Thread[WORKER_THREADS];
    for (int i = 0; i < WORKER_THREADS; i++) {
      final int idx = i;
      workers[i] =
          new Thread(() -> runWorker(provider, idx, stop), "soak-worker-" + idx);
      workers[i].start();
    }
    return workers;
  }

  private static void runWorker(L2Provider provider, int threadIdx, AtomicBoolean stop) {
    Random rng = new Random(0xC0FFEEL + threadIdx);
    byte[] payload = new byte[PAYLOAD_BYTES];
    while (!stop.get()) {
      String key = "k-" + rng.nextInt(KEY_POOL);
      int roll = rng.nextInt(100);
      rng.nextBytes(payload);
      if (roll < 70) {
        provider.put(key, payload);
      } else if (roll < 95) {
        provider.get(key);
      } else {
        provider.invalidate(key);
      }
    }
  }

  private static Sample snapshot(L2Provider provider, MemoryMXBean memBean) {
    return new Sample(
        Instant.now(),
        provider.stats().currentBytes(),
        provider.stats().currentEntries(),
        memBean.getHeapMemoryUsage().getUsed());
  }

  /**
   * Compares the minute-5 sample (post-warm-up baseline) against the
   * end-of-run sample for currentBytes, currentEntries, and heap used.
   * Assertion skipped when fewer than {@value #BASELINE_SAMPLE_INDEX} +
   * 1 samples were captured (short-duration runs).
   */
  private static void verifyAnchorDelta(List<Sample> samples) {
    assumeThat(samples.size())
        .as(
            "soak duration must allow at least %d minute samples for the anchor compare",
            BASELINE_SAMPLE_INDEX + 1)
        .isGreaterThan(BASELINE_SAMPLE_INDEX);
    Sample baseline = samples.get(BASELINE_SAMPLE_INDEX);
    Sample end = samples.get(samples.size() - 1);
    assertWithinNoiseBand("currentBytes", baseline.currentBytes(), end.currentBytes());
    assertWithinNoiseBand("currentEntries", baseline.currentEntries(), end.currentEntries());
    assertWithinNoiseBand("heapUsed", baseline.heapUsed(), end.heapUsed());
  }

  private static void assertWithinNoiseBand(String metric, long baseline, long end) {
    if (baseline == 0L) {
      return;
    }
    double delta = Math.abs((double) (end - baseline) / baseline);
    assertThat(delta)
        .withFailMessage(
            "%s drifted %.1f%% (baseline=%d, end=%d) — exceeds %d%% noise band",
            metric, delta * 100, baseline, end, NOISE_BAND_PERCENT)
        .isLessThanOrEqualTo(NOISE_BAND);
  }

  private static Duration parseDuration() {
    String s = System.getProperty("l2.soak.duration");
    if (s == null || s.isBlank()) {
      return DEFAULT_DURATION;
    }
    return Duration.parse(s);
  }

  private static L2Provider newProvider(ProviderKind kind, Path tempDir) {
    return switch (kind) {
      case SLAB ->
          new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, new SimpleMeterRegistry(), "soak");
      case LMDB ->
          new LmdbL2Provider(tempDir, MAX_BYTES, new SimpleMeterRegistry(), "soak");
      case CHAIN -> {
        SlabOffHeapL2Provider slab =
            new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, new SimpleMeterRegistry(), "soak-slab");
        LmdbL2Provider lmdb =
            new LmdbL2Provider(tempDir, MAX_BYTES, new SimpleMeterRegistry(), "soak-lmdb");
        yield new ChainedL2Provider(List.of(slab, lmdb));
      }
    };
  }

  /**
   * Snapshot of cache-tier and JVM heap state at one sampling interval.
   * Used by {@link #verifyAnchorDelta} for the minute-5-vs-end compare.
   */
  private record Sample(Instant taken, long currentBytes, long currentEntries, long heapUsed) {}
}
