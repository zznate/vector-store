package io.github.zznate.vectorstore.api.dto;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateIndexRequest(
    @NotBlank
        @Size(min = 1, max = 128)
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,126}[a-z0-9]|[a-z0-9]")
        String indexId,
    @NotBlank @Size(min = 1, max = 255) String displayName,
    @Min(1) int dimension,
    @NotNull DistanceMetric metric,
    Map<String, Object> engineParams) {}
