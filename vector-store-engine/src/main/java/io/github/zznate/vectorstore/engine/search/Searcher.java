package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.util.Bits;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import java.util.List;
import java.util.Set;

/**
 * Performs a single-segment kNN search. Higher-level query coordination
 * (manifest resolution, fan-out across multiple segments, merge) is the
 * {@link QueryCoordinator}'s job.
 */
public interface Searcher {

  /**
   * Search {@code segment} for the top-{@code topK} hits of
   * {@code queryVector}, restricted to graph ordinals in {@code accept}.
   * Returns in descending score order.
   */
  List<ScoredOrdinal> search(Segment segment, float[] queryVector, int topK, Bits accept);

  /**
   * Build a {@link Bits} accept mask for {@code segment} that admits every
   * ordinal except those whose user ID appears in {@code deniedUserIds}.
   * Uses the per-segment ordinal map (cached internally) to translate.
   */
  Bits buildAcceptMask(Segment segment, Set<String> deniedUserIds);

  /**
   * True if {@code userId} is present in {@code segment}'s ordinal map.
   * Drives {@code GET /vectors/{id}} on the query path.
   */
  boolean contains(Segment segment, String userId);
}
