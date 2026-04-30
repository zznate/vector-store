package io.github.zznate.vectorstore.engine.search;

/**
 * Per-query JVector search-time knobs. Maps directly onto the
 * {@code GraphSearcher.search(ssp, topK, rerankK, threshold, rerankFloor, bits)}
 * signature so future JVector tuning surfaces (cost-based pruning,
 * adaptive rerank) slot in here without wider API churn.
 *
 * <ul>
 *   <li>{@code rerankK} — number of candidates to rerank with exact
 *       scores after the approximate phase. {@link #defaults(int)} sets
 *       this to {@code topK} (correct for InlineVectors-only segments
 *       where the candidate pool is already exact). Once PQ is adopted
 *       a wider rerank pool than {@code topK} becomes meaningful.
 *   <li>{@code threshold} — minimum approximate similarity for a
 *       candidate to be admitted to the rerank pool.
 *   <li>{@code rerankFloor} — minimum exact similarity for a reranked
 *       hit to be returned.
 * </ul>
 *
 * <p>Both float thresholds default to {@code 0.0f} — i.e., "no cut".
 * Negative values are rejected; cosine and dot-product yield scores in
 * {@code [0, 1]} after JVector normalisation.
 *
 * <p>Construction shape: start from {@link #defaults(int)} and override
 * named knobs via the {@code withX} methods. {@code null} arguments are
 * no-ops, which lets callers thread optional request fields through a
 * chain without per-field ternaries:
 *
 * <pre>{@code
 *   SearchTuning tuning = SearchTuning.defaults(request.topK())
 *       .withRerankK(request.rerankK())          // null leaves rerankK = topK
 *       .withThreshold(request.threshold())
 *       .withRerankFloor(request.rerankFloor());
 * }</pre>
 *
 * <p>The named-mutator shape protects against the float/float swap that
 * a 3-arg positional constructor invites at call sites.
 */
public record SearchTuning(int rerankK, float threshold, float rerankFloor) {

  public SearchTuning {
    if (rerankK < 1) {
      throw new IllegalArgumentException("rerankK must be >= 1, got " + rerankK);
    }
    if (threshold < 0.0f) {
      throw new IllegalArgumentException("threshold must be >= 0.0, got " + threshold);
    }
    if (rerankFloor < 0.0f) {
      throw new IllegalArgumentException("rerankFloor must be >= 0.0, got " + rerankFloor);
    }
  }

  /** Sensible defaults for a query: rerankK=topK, no thresholds. */
  public static SearchTuning defaults(int topK) {
    return new SearchTuning(topK, 0.0f, 0.0f);
  }

  /** Override {@code rerankK}; {@code null} leaves the existing value unchanged. */
  public SearchTuning withRerankK(Integer rerankK) {
    return rerankK == null ? this : new SearchTuning(rerankK, threshold, rerankFloor);
  }

  /** Override {@code threshold}; {@code null} leaves the existing value unchanged. */
  public SearchTuning withThreshold(Float threshold) {
    return threshold == null ? this : new SearchTuning(rerankK, threshold, rerankFloor);
  }

  /** Override {@code rerankFloor}; {@code null} leaves the existing value unchanged. */
  public SearchTuning withRerankFloor(Float rerankFloor) {
    return rerankFloor == null ? this : new SearchTuning(rerankK, threshold, rerankFloor);
  }
}
