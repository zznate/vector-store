package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class IndexNotFoundException extends VectorStoreException {

  public IndexNotFoundException(String indexId) {
    super(Status.NOT_FOUND, "index_not_found", "Index not found: " + indexId);
  }
}
