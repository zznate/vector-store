package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class BucketNotFoundException extends VectorStoreException {

  public BucketNotFoundException(String bucketId) {
    super(Status.NOT_FOUND, "bucket_not_found", "Bucket not found: " + bucketId);
  }
}
