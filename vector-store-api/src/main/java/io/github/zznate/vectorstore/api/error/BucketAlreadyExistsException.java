package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

public final class BucketAlreadyExistsException extends VectorStoreException {

  public BucketAlreadyExistsException(String bucketId) {
    super(Status.CONFLICT, "bucket_already_exists", "Bucket already exists: " + bucketId);
  }
}
