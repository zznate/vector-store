package io.github.zznate.vectorstore.app.testresource;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * {@link QuarkusTestResourceLifecycleManager} that boots a MinIO container,
 * auto-creates the configured bucket, and feeds the dynamic endpoint plus
 * fixed credentials into the Quarkus test profile so the phase-3 {@code
 * SegmentStore} can be exercised end-to-end.
 *
 * <p>Container image is pinned so CI is reproducible. One container per
 * test class by default; Quarkus reuses it across tests in the same class.
 */
public final class MinioTestResource implements QuarkusTestResourceLifecycleManager {

  private static final Logger LOG = LoggerFactory.getLogger(MinioTestResource.class);

  private static final DockerImageName IMAGE =
      DockerImageName.parse("minio/minio:RELEASE.2025-01-20T14-49-07Z");

  public static final String ACCESS_KEY = "minioadmin";
  public static final String SECRET_KEY = "minioadmin";

  private MinIOContainer container;
  private String bucket;

  @Override
  public Map<String, String> start() {
    container = new MinIOContainer(IMAGE).withUserName(ACCESS_KEY).withPassword(SECRET_KEY);
    container.start();

    bucket = "vectorstore-it-" + Long.toHexString(System.nanoTime());
    try (S3Client admin = buildAdminClient(container.getS3URL())) {
      try {
        admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      } catch (NoSuchBucketException e) {
        throw new IllegalStateException("failed to create MinIO bucket " + bucket, e);
      }
    }
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "MinIO test container started endpoint={} bucket={}",
          container.getS3URL(),
          bucket);
    }

    Map<String, String> overrides = new HashMap<>();
    overrides.put("vectorstore.segments.store", "s3");
    overrides.put("vectorstore.storage.endpoint", container.getS3URL());
    overrides.put("vectorstore.storage.bucket", bucket);
    overrides.put("vectorstore.storage.access-key", ACCESS_KEY);
    overrides.put("vectorstore.storage.secret-key", SECRET_KEY);
    overrides.put("vectorstore.storage.region", "us-east-1");
    overrides.put("vectorstore.storage.path-style-access", "true");
    return overrides;
  }

  @Override
  public void stop() {
    if (container != null) {
      container.stop();
    }
  }

  private static S3Client buildAdminClient(String endpoint) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();
  }
}
