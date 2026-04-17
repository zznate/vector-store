package io.github.zznate.vectorstore.api.dto;

public record StatsResponse(
    int segmentCount, long vectorCount, long totalBytes, long pendingVectorCount) {}
