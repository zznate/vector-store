package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

public record Bucket(String bucketId, String displayName, Instant createdAt) {

  public Bucket {
    Objects.requireNonNull(bucketId, "bucketId");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
