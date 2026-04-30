package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Catalog row for a vector index.
 *
 * <p>{@code deletedAt} is {@code null} for active rows and set to the
 * soft-delete instant for rows in the retention window. Active read paths
 * filter on {@code deletedAt IS NULL} at the DAO layer; sweep and restore
 * are the only operations that look at soft-deleted rows. Use {@link
 * #active(String, String, String, int, DistanceMetric, String, Instant)}
 * for new-index construction so the intent is explicit at the call site.
 */
public record VectorIndex(
    String indexId,
    String bucketId,
    String displayName,
    int dimension,
    DistanceMetric metric,
    String engineParams,
    Instant createdAt,
    Instant deletedAt) {

  public VectorIndex {
    Objects.requireNonNull(indexId, "indexId");
    Objects.requireNonNull(bucketId, "bucketId");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(metric, "metric");
    Objects.requireNonNull(engineParams, "engineParams");
    Objects.requireNonNull(createdAt, "createdAt");
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be > 0, got " + dimension);
    }
  }

  public static VectorIndex active(
      String indexId,
      String bucketId,
      String displayName,
      int dimension,
      DistanceMetric metric,
      String engineParams,
      Instant createdAt) {
    return new VectorIndex(
        indexId, bucketId, displayName, dimension, metric, engineParams, createdAt, null);
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
