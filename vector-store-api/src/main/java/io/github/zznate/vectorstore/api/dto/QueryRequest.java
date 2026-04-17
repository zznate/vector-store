package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record QueryRequest(
    @NotNull @Size(min = 1) float[] vector,
    @Min(1) @Max(1000) int topK,
    Map<String, String> filter) {}
