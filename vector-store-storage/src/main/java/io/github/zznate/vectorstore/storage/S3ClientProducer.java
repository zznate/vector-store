package io.github.zznate.vectorstore.storage;

import io.github.zznate.vectorstore.storage.config.StorageConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Builds the application-wide {@link S3Client} from {@link StorageConfig}.
 *
 * <p>Uses the URL-connection HTTP client so we don't pull Netty onto the
 * classpath. Connection and read timeouts are short enough to fail a stuck
 * MinIO fast without tripping over the default AWS SDK tail latencies.
 *
 * <p>MinIO requires path-style addressing; real S3 accepts either. The flag
 * comes from config so a deployment targeting real S3 can flip it off.
 */
@ApplicationScoped
public class S3ClientProducer {

  private static final Logger LOG = LoggerFactory.getLogger(S3ClientProducer.class);

  @Produces
  @Singleton
  public S3Client s3Client(StorageConfig config) {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Building S3Client endpoint={} region={} bucket={} pathStyle={}",
          config.endpoint(),
          config.region(),
          config.bucket(),
          config.pathStyleAccess());
    }
    AwsBasicCredentials creds =
        AwsBasicCredentials.create(config.accessKey(), config.secretKey());
    return S3Client.builder()
        .endpointOverride(URI.create(config.endpoint()))
        .region(Region.of(config.region()))
        .credentialsProvider(StaticCredentialsProvider.create(creds))
        .serviceConfiguration(
            S3Configuration.builder()
                .pathStyleAccessEnabled(config.pathStyleAccess())
                .build())
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(2))
                .socketTimeout(Duration.ofSeconds(30)))
        .build();
  }

  public void closeS3Client(@Disposes S3Client client) {
    client.close();
  }
}
