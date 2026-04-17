package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class ForbiddenException extends VectorStoreException {

  public ForbiddenException(String message) {
    super(Status.FORBIDDEN, "forbidden", message);
  }
}
