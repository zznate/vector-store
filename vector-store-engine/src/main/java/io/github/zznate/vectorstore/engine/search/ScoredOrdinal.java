package io.github.zznate.vectorstore.engine.search;

import java.util.Objects;

/**
 * One hit from a single-segment kNN search: the graph ordinal, the
 * user-facing ID that ordinal maps to, and the similarity score.
 *
 * <p>Higher {@code score} is a better match across every supported
 * {@link io.github.zznate.vectorstore.core.catalog.model.DistanceMetric}.
 */
public record ScoredOrdinal(int ordinal, String userId, float score) {

  public ScoredOrdinal {
    Objects.requireNonNull(userId, "userId");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be >= 0, got " + ordinal);
    }
  }
}
