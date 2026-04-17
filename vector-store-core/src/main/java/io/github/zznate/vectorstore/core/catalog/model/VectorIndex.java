package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

public record VectorIndex(
    String indexId,
    String bucketId,
    String displayName,
    int dimension,
    DistanceMetric metric,
    String engineParams,
    Instant createdAt) {

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
}
