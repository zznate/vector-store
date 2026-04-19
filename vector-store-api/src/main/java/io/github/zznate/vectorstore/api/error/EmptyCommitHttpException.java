package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Raised when {@code :commit} finds nothing in the write buffer.
 */
public final class EmptyCommitHttpException extends VectorStoreException {

  public EmptyCommitHttpException(String indexId) {
    super(
        Status.CONFLICT,
        "empty_commit",
        "write buffer is empty for index " + indexId);
  }
}
