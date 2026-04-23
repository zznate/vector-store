package io.github.zznate.vectorstore.app.metrics;

/**
 * Canonical names for every Micrometer meter the vector-store service emits.
 * Call sites reference these constants so the observable surface stays stable
 * across modules and prompts.
 *
 * <p>Source of truth: {@code docs/design-notes.md} > "Metrics". Any new meter
 * needs an entry there first, then a constant here.
 *
 * <p>Tag keys that appear across multiple meters are named as {@code TAG_*}
 * to keep their spelling uniform.
 */
public final class MetricNames {

  private MetricNames() {}

  // --- Ingest -------------------------------------------------------------

  /** Counter: vectors accepted into an index's write buffer. */
  public static final String PUT_VECTORS = "vectorstore.put.vectors";

  // --- Commit (phase 2+) --------------------------------------------------

  /** Timer: wall time of a commit, broken down by phase tag. */
  public static final String COMMIT_DURATION = "vectorstore.commit.duration";

  /** DistributionSummary: bytes of the segment produced by a commit. */
  public static final String COMMIT_SEGMENT_BYTES = "vectorstore.commit.segment_bytes";

  // --- Query (phase 2+) ---------------------------------------------------

  /** Timer: wall time of a query fan-out + merge. */
  public static final String QUERY_DURATION = "vectorstore.query.duration";

  /** DistributionSummary: graph nodes visited during a query. */
  public static final String QUERY_NODES_VISITED = "vectorstore.query.nodes_visited";

  // --- Storage (phase 3+) -------------------------------------------------

  /** Timer: ranged object-store GET latency, tagged by cache_hit. */
  public static final String STORAGE_GET_DURATION = "vectorstore.storage.get.duration";

  /** Counter: bytes transferred, tagged by direction. */
  public static final String STORAGE_GET_BYTES = "vectorstore.storage.get.bytes";

  // --- Cache tiers (phase 5+) ---------------------------------------------

  /** Counter: cache hits, tagged by tier + cache_name. */
  public static final String CACHE_HIT = "vectorstore.cache.hit";

  /** Counter: cache misses, tagged by tier + cache_name. */
  public static final String CACHE_MISS = "vectorstore.cache.miss";

  /** Counter: cache evictions, tagged by tier + cache_name. */
  public static final String CACHE_EVICTION = "vectorstore.cache.eviction";

  /** Gauge: current bytes held by a cache tier. */
  public static final String CACHE_BYTES_CURRENT = "vectorstore.cache.bytes.current";

  /** Gauge: current entry count in a cache tier. */
  public static final String CACHE_ENTRIES_CURRENT = "vectorstore.cache.entries.current";

  // --- Filter (phase 4+) --------------------------------------------------

  /** Timer: cost to compile a filter predicate into a Bits mask. */
  public static final String FILTER_COMPILE_DURATION = "vectorstore.filter.compile.duration";

  // --- Common tag keys ----------------------------------------------------

  public static final String TAG_INDEX_ID = "index_id";
  public static final String TAG_SEGMENT_ID = "segment_id";
  public static final String TAG_PHASE = "phase";
  public static final String TAG_CACHE_HIT = "cache_hit";
  public static final String TAG_DIRECTION = "direction";
  public static final String TAG_TIER = "tier";
  public static final String TAG_CACHE_NAME = "cache_name";
}
