package io.github.zznate.vectorstore.engine.search;

import java.util.Map;
import java.util.Objects;

/**
 * Result of a fan-out kNN query: the user-facing ID, the JVector
 * similarity score, and the attributes for that vector (pulled from the
 * owning segment's sidecar).
 *
 * <p>{@link ScoredOrdinal} is the narrower per-segment shape the
 * {@link Searcher} emits; {@code ScoredHit} is the coordinator-level
 * shape with the segment identity erased and the metadata joined in.
 */
public record ScoredHit(String userId, float score, Map<String, String> attributes) {

  public ScoredHit {
    Objects.requireNonNull(userId, "userId");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
