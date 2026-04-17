package io.github.zznate.vectorstore.app.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Eagerly registers every meter named in {@link MetricNames} so the metric
 * surface is stable and documented from the first scrape, even before any
 * traffic has flowed.
 *
 * <p>These tag-less registrations serve as the canonical descriptions and
 * base units for each meter; dimensional increments at call sites will
 * register per-tag meter instances that inherit those descriptions through
 * the registry's meter-filter plumbing.
 */
@ApplicationScoped
public class VectorStoreMeters {

  private final MeterRegistry registry;

  @Inject
  public VectorStoreMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  void onStart(@Observes StartupEvent event) {
    Counter.builder(MetricNames.PUT_VECTORS)
        .description("Vectors accepted into the write buffer")
        .baseUnit("vectors")
        .register(registry);

    Timer.builder(MetricNames.COMMIT_DURATION)
        .description("Wall time of a commit, tagged by phase")
        .register(registry);

    DistributionSummary.builder(MetricNames.COMMIT_SEGMENT_BYTES)
        .description("Bytes of the segment produced by a commit")
        .baseUnit("bytes")
        .register(registry);

    Timer.builder(MetricNames.QUERY_DURATION)
        .description("Wall time of a query fan-out + merge")
        .register(registry);

    DistributionSummary.builder(MetricNames.QUERY_NODES_VISITED)
        .description("Graph nodes visited during a query")
        .baseUnit("nodes")
        .register(registry);

    Timer.builder(MetricNames.STORAGE_GET_DURATION)
        .description("Ranged object-store GET latency")
        .register(registry);

    Counter.builder(MetricNames.STORAGE_GET_BYTES)
        .description("Bytes transferred against the object store")
        .baseUnit("bytes")
        .register(registry);

    Counter.builder(MetricNames.CACHE_BLOCK_HIT)
        .description("Block-cache hits")
        .register(registry);

    Counter.builder(MetricNames.CACHE_BLOCK_MISS)
        .description("Block-cache misses")
        .register(registry);

    Timer.builder(MetricNames.FILTER_COMPILE_DURATION)
        .description("Cost of compiling a filter predicate into a Bits mask")
        .register(registry);
  }
}
