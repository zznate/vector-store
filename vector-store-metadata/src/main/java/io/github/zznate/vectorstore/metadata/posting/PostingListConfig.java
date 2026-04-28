package io.github.zznate.vectorstore.metadata.posting;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration for the per-segment posting-list sidecar.
 *
 * <p>The cardinality cap is the upper bound on distinct values per key
 * for which the writer will emit posting lists. Keys whose total
 * distinct-value count exceeds this threshold are skipped entirely;
 * filters against those keys fall back to the brute-force ordinal scan
 * at query time. The default of 10,000 is chosen so a moderate-cardinality
 * key (region, category, tier) is always indexed while a near-unique
 * identifier (user id, request id, document id) is not.
 */
@ConfigMapping(prefix = "vectorstore.metadata.posting-list")
public interface PostingListConfig {

  /** Per-key distinct-value cap above which the writer skips posting-list emission. */
  @WithName("max-cardinality")
  @WithDefault("10000")
  int maxCardinality();
}
