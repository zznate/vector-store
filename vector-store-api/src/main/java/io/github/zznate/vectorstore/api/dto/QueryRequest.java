package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * kNN query request. {@code filter} is optional; when present, every
 * value must be a plain string (phase-1 equality grammar). Nested maps,
 * arrays, numbers, or booleans are rejected with {@code 400
 * unsupported_operator} — phase 2 extends the grammar with {@code $in},
 * {@code $or}, and range operators.
 */
public record QueryRequest(
    @NotNull @Size(min = 1) float[] vector,
    @Min(1) @Max(1000) int topK,
    Map<String, Object> filter) {}
