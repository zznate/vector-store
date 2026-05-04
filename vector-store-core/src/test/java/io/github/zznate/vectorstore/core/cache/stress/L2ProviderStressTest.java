package io.github.zznate.vectorstore.core.cache.stress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.ChainedL2Provider;
import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.github.zznate.vectorstore.core.cache.SlabOffHeapL2Provider;
import io.github.zznate.vectorstore.core.cache.stress.scenarios.EvictionChurnScenario;
import io.github.zznate.vectorstore.core.cache.stress.scenarios.ReadHeavyScenario;
import io.github.zznate.vectorstore.core.cache.stress.scenarios.WriteHeavyScenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Concurrent stress against {@link L2Provider} implementations across the
 * read-heavy, write-heavy, and eviction-churn scenarios. Each scenario
 * runs against the slab provider, the LMDB provider, and a chain wrapping
 * both — nine combinations at default intensity in the unit suite.
 *
 * <p>The harness owns invariant checking; this test class is the wiring
 * layer that constructs each provider with scenario-specific sizing and
 * forwards to {@link L2ProviderStressHarness#run}. Failures surface from
 * the harness's {@code AssertJ} assertions with the scenario name in the
 * message so the offending combination is identifiable from the build
 * log.
 */
class L2ProviderStressTest {

  private static final long SEED = 1234567L;

  private final L2ProviderStressHarness harness = new L2ProviderStressHarness();

  static Stream<Arguments> scenarioByProvider() {
    List<StressScenario> scenarios =
        List.of(new ReadHeavyScenario(), new WriteHeavyScenario(), new EvictionChurnScenario());
    return scenarios.stream()
        .flatMap(
            s ->
                Stream.of(ProviderKind.SLAB, ProviderKind.LMDB, ProviderKind.CHAIN)
                    .map(k -> Arguments.of(s, k)));
  }

  @ParameterizedTest(name = "{0} × {1}")
  @MethodSource("scenarioByProvider")
  void defaultIntensityRunsClean(StressScenario scenario, ProviderKind kind, @TempDir Path tempDir) {
    StressConfig config = scenario.defaultConfig(kind, SEED);
    MeterRegistry registry = new SimpleMeterRegistry();
    L2Provider provider = newProvider(scenario, kind, tempDir, registry);
    try {
      StressRunResult result = harness.run(provider, config);
      assertThat(result.failed())
          .withFailMessage(
              "scenario %s × %s: %d worker exception(s)",
              scenario.name(), kind, result.workerExceptions().size())
          .isFalse();
    } finally {
      provider.close();
    }
  }

  private static L2Provider newProvider(
      StressScenario scenario, ProviderKind kind, Path tempDir, MeterRegistry registry) {
    long maxBytes = scenario.maxBytesFor(kind);
    return switch (kind) {
      case SLAB ->
          new SlabOffHeapL2Provider(maxBytes, scenario.slabBlockSize(), registry, "stress");
      case LMDB -> new LmdbL2Provider(tempDir, maxBytes, registry, "stress");
      case CHAIN -> newChain(scenario, tempDir, maxBytes, registry);
    };
  }

  private static ChainedL2Provider newChain(
      StressScenario scenario, Path tempDir, long perTierMaxBytes, MeterRegistry registry) {
    SlabOffHeapL2Provider slab =
        new SlabOffHeapL2Provider(
            perTierMaxBytes, scenario.slabBlockSize(), registry, "stress-slab");
    LmdbL2Provider lmdb = new LmdbL2Provider(tempDir, perTierMaxBytes, registry, "stress-lmdb");
    return new ChainedL2Provider(List.of(slab, lmdb));
  }
}
