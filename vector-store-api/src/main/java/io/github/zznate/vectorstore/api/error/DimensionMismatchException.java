package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Raised when an inbound vector's dimension does not match the target
 * index's declared dimension.
 */
public final class DimensionMismatchException extends VectorStoreException {

  public DimensionMismatchException(String indexId, int expected, int got) {
    super(
        Status.BAD_REQUEST,
        "dimension_mismatch",
        "index %s expects %d-dim vectors, got %d".formatted(indexId, expected, got));
  }
}
