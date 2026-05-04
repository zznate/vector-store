package io.github.zznate.vectorstore.core.cache.stress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-cycle randomised restart test for {@link LmdbL2Provider}. A
 * shared {@code @TempDir} backs every cycle, so each cycle inherits the
 * on-disk state left by the previous cycle's clean close. Per cycle:
 * open, apply a seeded single-threaded workload (mutating both the
 * provider and a cumulative reference oracle), snapshot the oracle,
 * close, reopen on the same directory, and verify every snapshot key
 * resolves to the recorded bytes.
 *
 * <p>Layered on top of the deterministic 16-key
 * {@code warmRestartPreservesKeySet} unit test in
 * {@code LmdbL2ProviderTest}: this exercises N cycles with random
 * payload distributions to surface drift that a single cycle would
 * miss.
 */
class LmdbL2ProviderRestartCycleTest {

  private static final long MAX_BYTES = 16L << 20;
  private static final int CYCLES_DEFAULT = 20;
  private static final int CYCLES_NIGHTLY = 100;
  private static final int OPS_PER_CYCLE_DEFAULT = 200;
  private static final int OPS_PER_CYCLE_NIGHTLY = 500;
  private static final int KEY_POOL = 64;
  private static final int PAYLOAD_MIN = 16;
  private static final int PAYLOAD_MAX = 4 * 1024;
  private static final long BASE_SEED = 7777L;

  @Test
  void cumulativeRestartPreservesEverySnapshot(@TempDir Path tempDir) {
    Map<String, byte[]> oracle = new HashMap<>();
    for (int cycle = 0; cycle < CYCLES_DEFAULT; cycle++) {
      runCycle(tempDir, oracle, cycle, OPS_PER_CYCLE_DEFAULT);
    }
  }

  @Tag("stress-nightly")
  @Test
  void cumulativeRestartPreservesEverySnapshotNightly(@TempDir Path tempDir) {
    Map<String, byte[]> oracle = new HashMap<>();
    for (int cycle = 0; cycle < CYCLES_NIGHTLY; cycle++) {
      runCycle(tempDir, oracle, cycle, OPS_PER_CYCLE_NIGHTLY);
    }
  }

  private static void runCycle(Path tempDir, Map<String, byte[]> oracle, int cycle, int opsPerCycle) {
    long seed = BASE_SEED + cycle;
    try (LmdbL2Provider provider =
        new LmdbL2Provider(tempDir, MAX_BYTES, new SimpleMeterRegistry(), "restart-cycle")) {
      applySequence(provider, oracle, seed, opsPerCycle);
    }
    Map<String, byte[]> snapshot = new HashMap<>(oracle);
    try (LmdbL2Provider reopened =
        new LmdbL2Provider(tempDir, MAX_BYTES, new SimpleMeterRegistry(), "restart-cycle")) {
      verifySnapshot(reopened, snapshot, cycle);
    }
  }

  private static void applySequence(
      LmdbL2Provider provider, Map<String, byte[]> oracle, long seed, int ops) {
    Random rng = new Random(seed);
    for (int i = 0; i < ops; i++) {
      int roll = rng.nextInt(100);
      String key = "k-" + rng.nextInt(KEY_POOL);
      if (roll < 60) {
        int len = PAYLOAD_MIN + rng.nextInt(PAYLOAD_MAX - PAYLOAD_MIN + 1);
        byte[] payload = new byte[len];
        rng.nextBytes(payload);
        provider.put(key, payload);
        oracle.put(key, payload);
      } else if (roll < 85) {
        provider.get(key);
      } else {
        provider.invalidate(key);
        oracle.remove(key);
      }
    }
  }

  private static void verifySnapshot(
      LmdbL2Provider reopened, Map<String, byte[]> snapshot, int cycle) {
    for (Map.Entry<String, byte[]> e : snapshot.entrySet()) {
      Optional<byte[]> hit = reopened.get(e.getKey());
      assertThat(hit)
          .withFailMessage("cycle %d: key %s missing after reopen", cycle, e.getKey())
          .isPresent();
      assertThat(hit.get())
          .withFailMessage("cycle %d: bytes mismatch for key %s after reopen", cycle, e.getKey())
          .isEqualTo(e.getValue());
    }
    assertThat(reopened.stats().currentEntries())
        .withFailMessage(
            "cycle %d: reopened provider has fewer entries (%d) than snapshot (%d)",
            cycle, reopened.stats().currentEntries(), snapshot.size())
        .isGreaterThanOrEqualTo(snapshot.size());
  }
}
