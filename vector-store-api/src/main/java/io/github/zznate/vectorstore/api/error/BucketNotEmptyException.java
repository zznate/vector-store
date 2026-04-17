package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class BucketNotEmptyException extends VectorStoreException {

  public BucketNotEmptyException(String bucketId) {
    super(
        Status.CONFLICT,
        "bucket_not_empty",
        "Bucket still has indexes and cannot be deleted: " + bucketId);
  }
}
