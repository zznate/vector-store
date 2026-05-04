package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Thrown by resource methods whose implementation is deferred. Maps to
 * HTTP 501 with the structured error body.
 */
public final class NotImplementedException extends VectorStoreException {

  public NotImplementedException(String operation) {
    super(Status.NOT_IMPLEMENTED, "not_implemented", "%s is not implemented".formatted(operation));
  }
}
