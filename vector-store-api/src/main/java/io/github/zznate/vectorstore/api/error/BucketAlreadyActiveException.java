package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Thrown when a client tries to restore a bucket that is already active.
 * The post-condition "row is active" is already satisfied, but we surface
 * 409 rather than 200 so the client can distinguish "I already had it" from
 * "I just restored it" without having to inspect timestamps.
 */
public final class BucketAlreadyActiveException extends VectorStoreException {

  public BucketAlreadyActiveException(String bucketId) {
    super(Status.CONFLICT, "bucket_already_active", "Bucket is already active: " + bucketId);
  }
}
