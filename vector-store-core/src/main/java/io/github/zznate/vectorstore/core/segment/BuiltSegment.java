package io.github.zznate.vectorstore.core.segment;

import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A freshly-built segment sitting in a local temp directory, ready for
 * {@link SegmentStore#publish(BuiltSegment, String)} to move into its final
 * location. Produced by the engine's segment builder; consumed by the
 * {@code SegmentStore} implementation.
 */
public record BuiltSegment(
    String segmentId,
    Path tempDirectory,
    long vectorCount,
    long bytes,
    IndexBuildParams buildParams,
    Instant builtAt) {

  public BuiltSegment {
    Objects.requireNonNull(segmentId, "segmentId");
    Objects.requireNonNull(tempDirectory, "tempDirectory");
    Objects.requireNonNull(buildParams, "buildParams");
    Objects.requireNonNull(builtAt, "builtAt");
    if (vectorCount < 0) {
      throw new IllegalArgumentException("vectorCount must be >= 0, got " + vectorCount);
    }
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got " + bytes);
    }
  }
}
