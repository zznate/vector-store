package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;
import java.time.Instant;

/**
 * Thrown when a client tries to create a bucket whose id matches a row that
 * has been soft-deleted but is still inside the retention window. The client
 * must wait for the retention sweep to hard-delete the row, or restore it
 * (once the restore endpoint lands), before re-using the id.
 */
public final class BucketInRetentionException extends VectorStoreException {

  public BucketInRetentionException(String bucketId, Instant deletedAt) {
    super(
        Status.CONFLICT,
        "bucket_in_retention",
        "Bucket id is in the soft-delete retention window and cannot be reused: "
            + bucketId
            + " (deleted_at="
            + deletedAt
            + ")");
  }
}
