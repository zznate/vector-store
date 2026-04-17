package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Thrown by resource methods whose implementation is deferred to a later
 * prompt. Maps to HTTP 501 with the structured error body.
 */
public final class NotImplementedException extends VectorStoreException {

  public NotImplementedException(String operation, int promptNumber) {
    super(
        Status.NOT_IMPLEMENTED,
        "not_implemented",
        "%s lands in prompt %02d".formatted(operation, promptNumber));
  }
}
