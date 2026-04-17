package io.github.zznate.vectorstore.core.catalog.model;

import java.time.Instant;
import java.util.Objects;

public record ManifestVersion(
    String indexId, int version, String segmentIds, Instant createdAt) {

  public ManifestVersion {
    Objects.requireNonNull(indexId, "indexId");
    Objects.requireNonNull(segmentIds, "segmentIds");
    Objects.requireNonNull(createdAt, "createdAt");
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1, got " + version);
    }
  }
}
