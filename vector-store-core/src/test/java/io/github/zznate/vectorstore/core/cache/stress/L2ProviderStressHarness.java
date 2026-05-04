package io.github.zznate.vectorstore.core.cache.stress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.ChainedL2Provider;
import io.github.zznate.vectorstore.core.cache.L2Provider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Drives a {@link L2Provider} through a configured workload of N worker
 * threads × M ops, maintaining a {@link ConcurrentHashMap} reference
 * oracle to verify the provider's final state at end of run. Each worker
 * draws ops from a deterministic per-thread RNG ({@code seed + threadIndex})
 * so failures reproduce.
 *
 * <p>Per-key serialisation between the oracle and the provider runs
 * through {@link Map#compute} on the oracle: the lambda holds the
 * per-bin lock while it both writes to the provider and updates the
 * oracle, so concurrent puts/invalidates of the same key are observed
 * in the same order by both views. Reads bypass the oracle.
 *
 * <p>A {@link ReadWriteLock} layers above the per-key compute paths so
 * the optional periodic-invalidate-all auxiliary thread can take a
 * write-lock to atomically clear both provider and oracle. Workers hold
 * the read-lock for the duration of their compute lambda, so the bulk
 * clear cannot interleave with a half-finished mutation.
 *
 * <p>End-of-run verification splits by {@link StressConfig.Mode}:
 * tight mode asserts oracle-equality and exact byte accounting; eviction-
 * aware mode asserts the relaxed contract (bounded bytes, non-zero
 * eviction counter, no exceptions). Chain providers inflate byte
 * accounting by the tier count via {@link #writeThroughTierCount}.
 */
public final class L2ProviderStressHarness {

  public StressRunResult run(L2Provider provider, StressConfig config) {
    return new Run(provider, config).execute();
  }

  /** Per-{@link #run} mutable state, scoped so the harness instance stays reusable. */
  private static final class Run {

    private final L2Provider provider;
    private final StressConfig cfg;
    private final ConcurrentHashMap<String, byte[]> oracle = new ConcurrentHashMap<>();
    private final Counters counters = new Counters();
    private final List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
    private final ReadWriteLock oracleLock = new ReentrantReadWriteLock();

    Run(L2Provider provider, StressConfig cfg) {
      this.provider = provider;
      this.cfg = cfg;
    }

    StressRunResult execute() {
      Instant t0 = Instant.now();
      runWorkersAndInvalidator();
      Duration wallClock = Duration.between(t0, Instant.now());

      StressRunResult result = buildResult(wallClock);
      verifyEndOfRun(result);
      return result;
    }

    private void runWorkersAndInvalidator() {
      AtomicBoolean stop = new AtomicBoolean(false);
      Thread invalidator = startInvalidatorIfConfigured(stop);
      Thread[] workers = startWorkers();
      joinAll(workers);
      stopInvalidator(invalidator, stop);
    }

    private Thread[] startWorkers() {
      Thread[] workers = new Thread[cfg.threads()];
      for (int i = 0; i < cfg.threads(); i++) {
        final int idx = i;
        workers[i] =
            new Thread(() -> runWorker(idx), "stress-" + cfg.scenarioName() + "-" + idx);
        workers[i].setUncaughtExceptionHandler((t, e) -> exceptions.add(e));
      }
      for (Thread t : workers) {
        t.start();
      }
      return workers;
    }

    private Thread startInvalidatorIfConfigured(AtomicBoolean stop) {
      if (cfg.periodicInvalidateAllInterval() == null) {
        return null;
      }
      Thread t =
          new Thread(
              () -> runInvalidator(cfg.periodicInvalidateAllInterval(), stop),
              "stress-" + cfg.scenarioName() + "-invalidator");
      t.setDaemon(true);
      t.setUncaughtExceptionHandler((th, e) -> exceptions.add(e));
      t.start();
      return t;
    }

    private void stopInvalidator(Thread invalidator, AtomicBoolean stop) {
      if (invalidator == null) {
        return;
      }
      stop.set(true);
      invalidator.interrupt();
      try {
        invalidator.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exceptions.add(e);
      }
    }

    private void joinAll(Thread[] workers) {
      for (Thread t : workers) {
        try {
          t.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          exceptions.add(e);
        }
      }
    }

    private void runWorker(int threadIdx) {
      Random rng = new Random(cfg.seed() + threadIdx);
      long maxBytes = provider.stats().maxBytes();
      for (int op = 0; op < cfg.opsPerThread(); op++) {
        doOp(rng);
        if ((op + 1) % cfg.sampleEveryOps() == 0) {
          long current = provider.stats().currentBytes();
          counters.peakBytes.accumulateAndGet(current, Math::max);
          assertThat(current)
              .withFailMessage(
                  "scenario %s: currentBytes %d exceeded maxBytes %d during run",
                  cfg.scenarioName(), current, maxBytes)
              .isLessThanOrEqualTo(maxBytes);
        }
      }
    }

    private void runInvalidator(Duration interval, AtomicBoolean stop) {
      while (!stop.get()) {
        try {
          Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (stop.get()) {
          return;
        }
        oracleLock.writeLock().lock();
        try {
          provider.invalidateAll();
          oracle.clear();
          counters.invalidateAllCount.incrementAndGet();
        } finally {
          oracleLock.writeLock().unlock();
        }
      }
    }

    private void doOp(Random rng) {
      int roll = rng.nextInt(100);
      String key = "k-" + rng.nextInt(cfg.keyPoolSize());
      switch (cfg.opMix().pick(roll)) {
        case GET -> doGet(key);
        case PUT -> doPut(rng, key);
        case INVALIDATE -> doInvalidate(key);
      }
    }

    private void doGet(String key) {
      Optional<byte[]> hit = provider.get(key);
      counters.getCount.incrementAndGet();
      if (hit.isPresent()) {
        counters.getHits.incrementAndGet();
      } else {
        counters.getMisses.incrementAndGet();
      }
    }

    private void doPut(Random rng, String key) {
      int len =
          cfg.payloadMin() == cfg.payloadMax()
              ? cfg.payloadMin()
              : cfg.payloadMin() + rng.nextInt(cfg.payloadMax() - cfg.payloadMin() + 1);
      byte[] payload = new byte[len];
      rng.nextBytes(payload);
      oracleLock.readLock().lock();
      try {
        oracle.compute(
            key,
            (k, prior) -> {
              provider.put(k, payload);
              counters.putCount.incrementAndGet();
              return payload;
            });
      } finally {
        oracleLock.readLock().unlock();
      }
    }

    private void doInvalidate(String key) {
      oracleLock.readLock().lock();
      try {
        oracle.compute(
            key,
            (k, prior) -> {
              provider.invalidate(k);
              counters.invalidateCount.incrementAndGet();
              return null;
            });
      } finally {
        oracleLock.readLock().unlock();
      }
    }

    private StressRunResult buildResult(Duration wallClock) {
      long total =
          counters.getCount.get() + counters.putCount.get() + counters.invalidateCount.get();
      return new StressRunResult(
          cfg.scenarioName(),
          wallClock,
          total,
          counters.getCount.get(),
          counters.putCount.get(),
          counters.invalidateCount.get(),
          counters.invalidateAllCount.get(),
          counters.getHits.get(),
          counters.getMisses.get(),
          counters.peakBytes.get(),
          provider.stats().currentBytes(),
          provider.stats().evictionCount(),
          new ArrayList<>(exceptions));
    }

    private void verifyEndOfRun(StressRunResult result) {
      assertThat(result.workerExceptions())
          .withFailMessage(
              "scenario %s: %d worker exception(s); first: %s",
              cfg.scenarioName(),
              result.workerExceptions().size(),
              result.workerExceptions().isEmpty()
                  ? "none"
                  : result.workerExceptions().get(0).toString())
          .isEmpty();
      long maxBytes = provider.stats().maxBytes();
      assertThat(result.finalBytes())
          .withFailMessage(
              "scenario %s: finalBytes %d exceeds maxBytes %d",
              cfg.scenarioName(), result.finalBytes(), maxBytes)
          .isLessThanOrEqualTo(maxBytes);

      if (cfg.mode() == StressConfig.Mode.TIGHT) {
        verifyTightMode(result);
      } else {
        verifyEvictionAware(result);
      }
    }

    private void verifyTightMode(StressRunResult result) {
      int multiplier = writeThroughTierCount(provider);
      long oracleBytes = 0;
      for (Map.Entry<String, byte[]> e : oracle.entrySet()) {
        Optional<byte[]> hit = provider.get(e.getKey());
        assertThat(hit)
            .withFailMessage(
                "scenario %s: tight-mode key %s missing from provider",
                cfg.scenarioName(), e.getKey())
            .isPresent();
        assertThat(hit.get())
            .withFailMessage(
                "scenario %s: tight-mode bytes mismatch for key %s",
                cfg.scenarioName(), e.getKey())
            .isEqualTo(e.getValue());
        oracleBytes += e.getValue().length;
      }
      long expected = oracleBytes * multiplier;
      assertThat(result.finalBytes())
          .withFailMessage(
              "scenario %s: tight-mode bytes mismatch:"
                  + " provider=%d expected=%d (oracle=%d × tiers=%d)",
              cfg.scenarioName(), result.finalBytes(), expected, oracleBytes, multiplier)
          .isEqualTo(expected);
    }

    private void verifyEvictionAware(StressRunResult result) {
      for (Map.Entry<String, byte[]> e : oracle.entrySet()) {
        Optional<byte[]> hit = provider.get(e.getKey());
        if (hit.isPresent()) {
          assertThat(hit.get())
              .withFailMessage(
                  "scenario %s: eviction-aware surviving key %s has wrong bytes",
                  cfg.scenarioName(), e.getKey())
              .isEqualTo(e.getValue());
        }
      }
      assertThat(result.evictionCount())
          .withFailMessage(
              "scenario %s: expected eviction counter > 0, got %d",
              cfg.scenarioName(), result.evictionCount())
          .isGreaterThan(0L);
    }
  }

  /**
   * Each tier in a chain holds its own copy under write-through, so the
   * tight-mode byte invariant inflates by tier count. Direct providers
   * return 1.
   */
  private static int writeThroughTierCount(L2Provider provider) {
    if (provider instanceof ChainedL2Provider chain) {
      return chain.providers().size();
    }
    return 1;
  }

  private static final class Counters {
    final AtomicLong getCount = new AtomicLong();
    final AtomicLong putCount = new AtomicLong();
    final AtomicLong invalidateCount = new AtomicLong();
    final AtomicLong invalidateAllCount = new AtomicLong();
    final AtomicLong getHits = new AtomicLong();
    final AtomicLong getMisses = new AtomicLong();
    final AtomicLong peakBytes = new AtomicLong();
  }
}
