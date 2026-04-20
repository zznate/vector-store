package io.github.zznate.vectorstore.app.testprofile;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Points the segment store at a bucket that does not exist in the running
 * MinIO container. Used by the commit-resilience integration test to force
 * {@code PutObject} to fail with {@code NoSuchBucket} without having to
 * drop tables or install an MinIO bucket policy mid-test.
 */
public final class NonexistentBucketProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "vectorstore.segments.store", "s3",
        "vectorstore.storage.bucket", "vectorstore-missing-bucket");
  }
}
