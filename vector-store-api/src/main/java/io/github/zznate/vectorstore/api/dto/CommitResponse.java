package io.github.zznate.vectorstore.api.dto;

import java.time.Instant;

public record CommitResponse(
    String segmentId,
    long vectorCount,
    long bytes,
    int manifestVersion,
    Instant committedAt) {}
