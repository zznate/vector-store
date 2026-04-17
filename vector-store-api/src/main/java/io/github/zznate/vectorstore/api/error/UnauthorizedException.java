package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class UnauthorizedException extends VectorStoreException {

  public UnauthorizedException(String message) {
    super(Status.UNAUTHORIZED, "unauthorized", message);
  }
}
