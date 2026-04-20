package io.github.zznate.vectorstore.storage.reader;

import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Per-(bucket, key) factory for {@link S3RandomAccessReader} instances.
 * JVector's {@code RandomAccessReader} contract is per-thread stateful, so
 * concurrent queries get fresh readers from the same supplier while sharing
 * the one object-length HEAD probe performed at construction.
 *
 * <p>{@link #close()} is a no-op: readers own no pooled resources and the
 * underlying {@link S3Client} is shared process-wide.
 */
public final class S3ReaderSupplier implements ReaderSupplier {

  private final S3Client s3Client;
  private final String bucket;
  private final String key;
  private final long objectLength;
  private final MeterRegistry meterRegistry;
  private final Tracer tracer;

  public S3ReaderSupplier(
      S3Client s3Client,
      String bucket,
      String key,
      MeterRegistry meterRegistry,
      Tracer tracer) {
    this.s3Client = s3Client;
    this.bucket = bucket;
    this.key = key;
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
    HeadObjectResponse head =
        s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    this.objectLength = head.contentLength();
  }

  public long objectLength() {
    return objectLength;
  }

  @Override
  public RandomAccessReader get() {
    return new S3RandomAccessReader(s3Client, bucket, key, objectLength, meterRegistry, tracer);
  }

  @Override
  public void close() {
    // No retained state to release.
  }
}
