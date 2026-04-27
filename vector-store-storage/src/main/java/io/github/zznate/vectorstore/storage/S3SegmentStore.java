package io.github.zznate.vectorstore.storage;

import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.zznate.vectorstore.core.cache.CachePolicy;
import io.github.zznate.vectorstore.core.cache.CachePolicyResolver;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.storage.cache.BlockCache;
import io.github.zznate.vectorstore.storage.config.StorageConfig;
import io.github.zznate.vectorstore.storage.reader.BlockCachingRandomAccessReader;
import io.github.zznate.vectorstore.storage.reader.S3RandomAccessReader;
import io.github.zznate.vectorstore.storage.reader.S3ReaderSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * {@link SegmentStore} that uploads every built segment to an S3-compatible
 * object store (MinIO in dev, real S3 in prod) and serves reads via
 * block-cached ranged {@code GetObject} calls.
 *
 * <p>Each segment root is the object prefix {@code <objectPrefix>/} inside
 * the configured bucket. Sidecars are uploaded as discrete keys under that
 * prefix. Uploads above {@link #MULTIPART_THRESHOLD_BYTES} use multipart
 * upload; smaller payloads go out as a single {@code PutObject}. If an
 * upload fails, the multipart upload is aborted so no phantom parts linger.
 *
 * <p>{@link S3ReaderSupplier}s are cached by segment id so repeated queries
 * share the one {@code HeadObject} probe of the graph object. The
 * {@link BlockCache} supplied by the CDI container is shared process-wide
 * across every reader.
 */
public class S3SegmentStore implements SegmentStore, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(S3SegmentStore.class);

  /** Switch to multipart upload at or above this payload size. */
  public static final long MULTIPART_THRESHOLD_BYTES = 8L * 1024 * 1024;

  /** Part size used for multipart uploads. S3 minimum is 5 MiB. */
  public static final int MULTIPART_PART_BYTES = 8 * 1024 * 1024;

  private final S3Client s3Client;
  private final String bucket;
  private final int blockSize;
  private final BlockCache blockCache;
  private final CachePolicyResolver cachePolicyResolver;
  private final MeterRegistry meterRegistry;
  private final Tracer tracer;
  private final ConcurrentHashMap<String, ReaderSupplier> graphSuppliers = new ConcurrentHashMap<>();

  public S3SegmentStore(
      S3Client s3Client,
      StorageConfig config,
      int blockSize,
      BlockCache blockCache,
      CachePolicyResolver cachePolicyResolver,
      MeterRegistry meterRegistry,
      Tracer tracer) {
    this.s3Client = s3Client;
    this.bucket = config.bucket();
    this.blockSize = blockSize;
    this.blockCache = blockCache;
    this.cachePolicyResolver = cachePolicyResolver;
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
  }

  @Override
  public URI publish(BuiltSegment local, String objectPrefix) throws IOException {
    String normalizedPrefix = stripTrailingSlash(objectPrefix);
    List<Path> uploaded = new ArrayList<>();
    try (var stream = Files.list(local.tempDirectory())) {
      List<Path> sources = stream.sorted().toList();
      for (Path src : sources) {
        String objectKey = normalizedPrefix + "/" + src.getFileName().toString();
        uploadFile(src, objectKey);
        uploaded.add(src);
      }
    } catch (IOException | RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "publish failed for segment={} prefix={} uploaded={} files",
            local.segmentId(),
            normalizedPrefix,
            uploaded.size(),
            e);
      }
      throw e;
    }
    for (Path src : uploaded) {
      Files.deleteIfExists(src);
    }
    Files.deleteIfExists(local.tempDirectory());
    return URI.create("s3://" + bucket + "/" + normalizedPrefix + "/");
  }

  @Override
  public ReaderSupplier openGraph(Segment segment) {
    String graphKey = stripTrailingSlash(segment.objectPrefix()) + "/graph.jvec";
    boolean useL2 = useL2For(segment);
    return graphSuppliers.computeIfAbsent(
        segment.segmentId(), id -> createSupplier(graphKey, useL2));
  }

  private boolean useL2For(Segment segment) {
    try {
      return cachePolicyResolver.policyFor(segment.indexId()) != CachePolicy.MINIMAL;
    } catch (RuntimeException e) {
      // Best-effort: if the policy can't be resolved, fall back to the L2
      // path so we don't accidentally degrade SMART/RESIDENT indexes.
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "could not resolve cache policy for index {}, defaulting to useL2=true",
            segment.indexId(),
            e);
      }
      return true;
    }
  }

  @Override
  public InputStream openSidecar(Segment segment, String fileName) {
    String key = stripTrailingSlash(segment.objectPrefix()) + "/" + fileName;
    return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void putSidecar(Segment segment, String fileName, byte[] content) {
    String key = stripTrailingSlash(segment.objectPrefix()) + "/" + fileName;
    s3Client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(),
        RequestBody.fromBytes(content));
  }

  /**
   * Drop every cached supplier. Safe to call repeatedly; used by the CDI
   * container on application shutdown.
   */
  @Override
  public void close() {
    graphSuppliers.clear();
  }

  private ReaderSupplier createSupplier(String objectKey, boolean useL2) {
    try {
      S3ReaderSupplier inner =
          new S3ReaderSupplier(s3Client, bucket, objectKey, meterRegistry, tracer);
      String cacheKey = bucket + "/" + objectKey;
      return new BlockCachingReaderSupplier(
          inner, cacheKey, blockSize, blockCache, meterRegistry, useL2);
    } catch (RuntimeException e) {
      throw new UncheckedIOException(
          new IOException("failed to open S3 reader supplier for " + objectKey, e));
    }
  }

  /**
   * Adapter that wraps each {@link S3ReaderSupplier#get()} call in a fresh
   * {@link BlockCachingRandomAccessReader}. JVector may call
   * {@link ReaderSupplier#get()} multiple times per segment (once for the
   * header, once per view) and each call must produce a reader with its own
   * position state; the shared {@link BlockCache} dedupes the actual cold
   * bytes across readers.
   */
  private static final class BlockCachingReaderSupplier implements ReaderSupplier {
    private final S3ReaderSupplier inner;
    private final String cacheKey;
    private final int blockSize;
    private final BlockCache blockCache;
    private final MeterRegistry meterRegistry;
    private final boolean useL2;

    BlockCachingReaderSupplier(
        S3ReaderSupplier inner,
        String cacheKey,
        int blockSize,
        BlockCache blockCache,
        MeterRegistry meterRegistry,
        boolean useL2) {
      this.inner = inner;
      this.cacheKey = cacheKey;
      this.blockSize = blockSize;
      this.blockCache = blockCache;
      this.meterRegistry = meterRegistry;
      this.useL2 = useL2;
    }

    @Override
    public RandomAccessReader get() {
      S3RandomAccessReader raw = (S3RandomAccessReader) inner.get();
      return new BlockCachingRandomAccessReader(
          raw, cacheKey, blockSize, inner.objectLength(), blockCache, meterRegistry, useL2);
    }

    @Override
    public void close() throws IOException {
      inner.close();
    }
  }

  private void uploadFile(Path localFile, String key) throws IOException {
    long size = Files.size(localFile);
    if (size < MULTIPART_THRESHOLD_BYTES) {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(),
          RequestBody.fromFile(localFile));
      return;
    }
    uploadMultipart(localFile, key, size);
  }

  private void uploadMultipart(Path localFile, String key, long size) throws IOException {
    CreateMultipartUploadResponse init =
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());
    String uploadId = init.uploadId();
    List<CompletedPart> parts = new ArrayList<>();
    try (InputStream in = Files.newInputStream(localFile)) {
      int partNumber = 1;
      byte[] buf = new byte[MULTIPART_PART_BYTES];
      long totalRead = 0;
      while (totalRead < size) {
        int filled = fillBuffer(in, buf);
        if (filled == 0) {
          break;
        }
        UploadPartResponse resp =
            s3Client.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength((long) filled)
                    .build(),
                RequestBody.fromBytes(filled == buf.length ? buf : Arrays.copyOf(buf, filled)));
        parts.add(CompletedPart.builder().partNumber(partNumber).eTag(resp.eTag()).build());
        totalRead += filled;
        partNumber++;
      }
      s3Client.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(key)
              .uploadId(uploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
              .build());
    } catch (IOException | RuntimeException e) {
      abortQuietly(key, uploadId);
      throw e;
    }
  }

  private static int fillBuffer(InputStream in, byte[] buf) throws IOException {
    int filled = 0;
    while (filled < buf.length) {
      int n = in.read(buf, filled, buf.length - filled);
      if (n < 0) {
        break;
      }
      filled += n;
    }
    return filled;
  }

  private void abortQuietly(String key, String uploadId) {
    try {
      s3Client.abortMultipartUpload(
          software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(key)
              .uploadId(uploadId)
              .build());
    } catch (RuntimeException e) {
      // Multipart abort is best-effort cleanup — the original upload
      // failure is already being thrown by the caller. We log at debug
      // (not warn) so a failed upload does not produce two stack traces
      // for operators to triage.
      if (LOG.isDebugEnabled()) {
        LOG.debug("abortMultipartUpload failed for key={} uploadId={}", key, uploadId, e);
      }
    }
  }

  private static String stripTrailingSlash(String prefix) {
    return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
  }
}
