package io.github.zznate.vectorstore.engine.build;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import java.time.Instant;

/**
 * Serialised contents of {@code header.json} — the segment-level metadata
 * file that accompanies {@code graph.jvec} on disk.
 *
 * <p>Captures enough to reopen and describe the segment without the
 * catalog: dimension, distance metric, build parameters, and a schema
 * version so later layout changes can fail loudly rather than silently
 * miscompute.
 */
public record SegmentHeader(
    int schemaVersion,
    String segmentId,
    long vectorCount,
    int dimension,
    DistanceMetric distanceMetric,
    IndexBuildParams engineParams,
    Instant builtAt) {

  public static final int CURRENT_SCHEMA_VERSION = 1;
}
