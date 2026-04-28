package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * kNN query request. {@code filter} is optional; when present it follows
 * the grammar parsed by {@link io.github.zznate.vectorstore.metadata.filter.FilterParser}
 * — equality leaves, {@code $in} set membership, {@code $or}, {@code $not},
 * and implicit AND of sibling keys. Range operators ({@code $gt}, {@code $lt},
 * {@code $gte}, {@code $lte}, {@code $between}) and non-string scalar values
 * are rejected with {@code 400 unsupported_operator}; top-level {@code $or}
 * mixed with sibling keys is rejected with {@code 400 bad_request}.
 */
public record QueryRequest(
    @NotNull @Size(min = 1) float[] vector,
    @Min(1) @Max(1000) int topK,
    Map<String, Object> filter) {}
