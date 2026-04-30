package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Thrown when a client tries to restore an index that is already active.
 * 409 rather than 200 so the client can distinguish "I already had it" from
 * "I just restored it".
 */
public final class IndexAlreadyActiveException extends VectorStoreException {

  public IndexAlreadyActiveException(String indexId) {
    super(Status.CONFLICT, "index_already_active", "Index is already active: " + indexId);
  }
}
