package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Catalog row for a bucket.
 *
 * <p>{@code deletedAt} is {@code null} for active rows and set to the
 * soft-delete instant for rows in the retention window. Most read paths
 * filter on {@code deletedAt IS NULL} at the DAO layer; only sweep and
 * restore-style operations look at soft-deleted rows. Use {@link
 * #active(String, String, Instant)} when constructing a fresh bucket so
 * the intent is explicit at the call site.
 */
public record Bucket(String bucketId, String displayName, Instant createdAt, Instant deletedAt) {

  public Bucket {
    Objects.requireNonNull(bucketId, "bucketId");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public static Bucket active(String bucketId, String displayName, Instant createdAt) {
    return new Bucket(bucketId, displayName, createdAt, null);
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
