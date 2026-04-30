package io.github.zznate.vectorstore.core.catalog.model;

import io.github.zznate.vectorstore.core.cache.CachePolicy;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Per-process default values for {@link IndexBuildParams}. Read at index
 * creation time when the caller's {@code engineParams} omits a field;
 * SmallRye Config layers env vars / system properties /
 * {@code application.properties} over the hard-coded {@code @WithDefault}
 * values here.
 *
 * <p>Once an index has segments, its persisted {@code engine_params}
 * become the authoritative source for that index — these globals only
 * affect indexes created or re-created after the change. JVector's
 * streaming compaction (PR #659) requires every segment in one
 * compaction job to share {@code m}, {@code addHierarchy}, and the
 * feature set, so per-index params freeze for the lifetime of an
 * index. Changing globals retroactively would not change any existing
 * index's behaviour.
 *
 * <p>Property keys are kebab-cased under {@code vectorstore.index.defaults.*}.
 * Env-var convention via SmallRye Config:
 * {@code VECTORSTORE_INDEX_DEFAULTS_M=64}.
 *
 * <p>{@code cacheBytes} is intentionally absent: it is an optional
 * per-index hint, not a global default.
 */
@ConfigMapping(prefix = "vectorstore.index.defaults")
public interface IndexBuildParamsDefaults {

  /** Graph degree (connections per node). */
  @WithDefault("32")
  int m();

  /** Candidate pool during construction (Vamana ef_construction). */
  @WithName("beam-width")
  @WithDefault("200")
  int beamWidth();

  /** Multiplier on the neighbour pool during insertion. */
  @WithName("neighbor-overflow")
  @WithDefault("1.2")
  float neighborOverflow();

  /** Vamana pruning threshold. */
  @WithDefault("1.2")
  float alpha();

  /** Product-quantisation subspaces. Must divide the vector dimension. */
  @WithName("pq-subspaces")
  @WithDefault("128")
  int pqSubspaces();

  /** Cluster count per PQ subspace. */
  @WithName("pq-subspace-clusters")
  @WithDefault("256")
  int pqSubspaceClusters();

  /** {@code false} = flat Vamana / DiskANN; {@code true} = hierarchical HNSW-style graph. */
  @WithName("add-hierarchy")
  @WithDefault("false")
  boolean addHierarchy();

  /** Default warm-tier cache policy: SMART | RESIDENT | MINIMAL. */
  @WithName("cache-policy")
  @WithDefault("SMART")
  CachePolicy cachePolicy();
}
