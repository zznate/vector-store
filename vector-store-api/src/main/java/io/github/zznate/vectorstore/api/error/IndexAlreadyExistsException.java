package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class IndexAlreadyExistsException extends VectorStoreException {

  public IndexAlreadyExistsException(String indexId) {
    super(Status.CONFLICT, "index_already_exists", "Index already exists: " + indexId);
  }
}
