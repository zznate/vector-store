package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

public record Segment(
    String segmentId,
    String indexId,
    SegmentState state,
    long vectorCount,
    long bytes,
    String objectPrefix,
    Instant createdAt) {

  public Segment {
    Objects.requireNonNull(segmentId, "segmentId");
    Objects.requireNonNull(indexId, "indexId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(objectPrefix, "objectPrefix");
    Objects.requireNonNull(createdAt, "createdAt");
    if (vectorCount < 0) {
      throw new IllegalArgumentException("vectorCount must be >= 0, got " + vectorCount);
    }
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got " + bytes);
    }
  }
}
