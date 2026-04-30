package io.github.zznate.vectorstore.engine.search;

import io.github.jbellis.jvector.util.Bits;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import java.util.List;
import java.util.Set;
import org.roaringbitmap.RoaringBitmap;

/**
 * Performs a single-segment kNN search. Higher-level query coordination
 * (manifest resolution, fan-out across multiple segments, merge) is the
 * {@link QueryCoordinator}'s job.
 */
public interface Searcher {

  /**
   * Search {@code segment} for the top-{@code topK} hits of
   * {@code queryVector}, restricted to graph ordinals in {@code accept}.
   * {@code tuning} carries the JVector search-time knobs (rerank pool
   * size, similarity thresholds); callers without a preference pass
   * {@link SearchTuning#defaults(int)}. Returns in descending score
   * order.
   */
  List<ScoredOrdinal> search(
      Segment segment, float[] queryVector, int topK, Bits accept, SearchTuning tuning);

  /**
   * Ordinal of {@code userId} within {@code segment}, or {@code -1} if
   * the user ID is not present. Drives both {@link #contains} and the
   * {@code GET /vectors/{id}} response path, which needs the ordinal to
   * read attributes from the sidecar.
   */
  int findOrdinal(Segment segment, String userId);

  /**
   * True if {@code userId} is present in {@code segment}'s ordinal map.
   */
  default boolean contains(Segment segment, String userId) {
    return findOrdinal(segment, userId) >= 0;
  }

  /**
   * Resolve each of {@code userIds} against {@code segment}'s ordinal map
   * and return a {@link RoaringBitmap} of the ordinals that match. IDs
   * not present in the segment are skipped; the returned bitmap is empty
   * when none match. Drives the commit-time tombstone merge.
   */
  RoaringBitmap ordinalsOf(Segment segment, Set<String> userIds);
}
