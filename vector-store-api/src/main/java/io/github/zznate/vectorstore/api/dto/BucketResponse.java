package io.github.zznate.vectorstore.api.dto;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import java.time.Instant;

public record BucketResponse(String bucketId, String displayName, Instant createdAt) {

  public static BucketResponse from(Bucket bucket) {
    return new BucketResponse(bucket.bucketId(), bucket.displayName(), bucket.createdAt());
  }
}
