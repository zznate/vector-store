package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * kNN query request.
 *
 * <p>{@code filter} is optional; when present it follows the grammar
 * parsed by {@link io.github.zznate.vectorstore.metadata.filter.FilterParser}
 * — equality leaves, {@code $in} set membership, {@code $or}, {@code $not},
 * and implicit AND of sibling keys. Range operators ({@code $gt}, {@code $lt},
 * {@code $gte}, {@code $lte}, {@code $between}) and non-string scalar values
 * are rejected with {@code 400 unsupported_operator}; top-level {@code $or}
 * mixed with sibling keys is rejected with {@code 400 bad_request}.
 *
 * <p>The {@code rerankK}, {@code threshold}, and {@code rerankFloor}
 * fields expose JVector's per-query search-time knobs. All three are
 * optional; absent fields default to {@code rerankK=topK},
 * {@code threshold=0.0}, {@code rerankFloor=0.0} — i.e., the
 * conservative behaviour for InlineVectors-only segments. Sophisticated
 * clients can widen the rerank pool or apply similarity cutoffs
 * per-query without an index-time recommit.
 */
public record QueryRequest(
    @NotNull @Size(min = 1) float[] vector,
    @Min(1) @Max(1000) int topK,
    Map<String, Object> filter,
    @Min(1) @Max(10000) Integer rerankK,
    @DecimalMin("0.0") Float threshold,
    @DecimalMin("0.0") Float rerankFloor) {}
