package io.github.zznate.vectorstore.api.dto;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import java.time.Instant;
import java.util.Map;

public record IndexResponse(
    String indexId,
    String bucketId,
    String displayName,
    int dimension,
    DistanceMetric metric,
    Map<String, Object> engineParams,
    Instant createdAt) {}
