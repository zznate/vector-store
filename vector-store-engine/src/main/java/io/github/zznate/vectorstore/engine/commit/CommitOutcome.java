package io.github.zznate.vectorstore.engine.commit;

import java.time.Instant;

/**
 * Result of a successful commit. The api layer maps this onto
 * {@code CommitResponse} for the HTTP surface.
 */
public record CommitOutcome(
    String segmentId,
    long vectorCount,
    long bytes,
    int manifestVersion,
    Instant committedAt) {}
