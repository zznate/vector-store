package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;
import java.time.Instant;

/**
 * Thrown when a client tries to create an index whose id matches a row that
 * has been soft-deleted but is still inside the retention window. The client
 * must wait for the retention sweep to hard-delete the row, or restore it
 * (once the restore endpoint lands), before re-using the id.
 */
public final class IndexInRetentionException extends VectorStoreException {

  public IndexInRetentionException(String indexId, Instant deletedAt) {
    super(
        Status.CONFLICT,
        "index_in_retention",
        "Index id is in the soft-delete retention window and cannot be reused: "
            + indexId
            + " (deleted_at="
            + deletedAt
            + ")");
  }
}
