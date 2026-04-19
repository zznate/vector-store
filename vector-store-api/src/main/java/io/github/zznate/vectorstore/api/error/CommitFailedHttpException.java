package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Raised when the engine's commit pipeline fails at any phase
 * ({@code build}, {@code publish}, or {@code catalog}). Maps to 500 — the
 * catalog has already been left in an auditable {@code RETIRED} state by
 * the coordinator, and {@code vectorstore.commit.failures} has been
 * incremented with the phase tag.
 */
public final class CommitFailedHttpException extends VectorStoreException {

  public CommitFailedHttpException(String indexId, String phase, Throwable cause) {
    super(
        Status.INTERNAL_SERVER_ERROR,
        "commit_failed",
        "commit failed for index %s during %s phase: %s"
            .formatted(indexId, phase, cause.getMessage()));
  }
}
