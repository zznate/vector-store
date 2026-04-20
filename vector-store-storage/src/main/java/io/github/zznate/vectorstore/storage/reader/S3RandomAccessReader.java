package io.github.zznate.vectorstore.storage.reader;

import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * {@link RandomAccessReader} backed by ranged {@code GetObject} calls against
 * a single S3 object. Stateful and <strong>not thread-safe</strong> — this
 * matches JVector's contract for {@code RandomAccessReader}. A
 * {@link S3ReaderSupplier} hands one out per caller.
 *
 * <p>Every read issues a single {@code GetObject} with a {@code Range} header.
 * Each call is wrapped in a {@code vectorstore.storage.range_get} span and its
 * duration is recorded into {@code vectorstore.storage.get.duration}; the byte
 * count contributes to {@code vectorstore.storage.get.bytes} tagged by
 * {@code direction=download}. The {@code cache_hit=false} tag is emitted here
 * because a hit on the upstream {@link BlockCachingRandomAccessReader} never
 * reaches this reader.
 *
 * <p>JVector writes its on-disk format in big-endian order (network order).
 * All numeric read paths assume that.
 */
public final class S3RandomAccessReader implements RandomAccessReader, RangeReader {

  private static final String SPAN_RANGE_GET = "vectorstore.storage.range_get";
  private static final String METER_GET_DURATION = "vectorstore.storage.get.duration";
  private static final String METER_GET_BYTES = "vectorstore.storage.get.bytes";
  private static final String TAG_CACHE_HIT = "cache_hit";
  private static final String TAG_DIRECTION = "direction";

  private static final AttributeKey<String> ATTR_BUCKET = AttributeKey.stringKey("s3.bucket");
  private static final AttributeKey<String> ATTR_KEY = AttributeKey.stringKey("s3.key");
  private static final AttributeKey<Long> ATTR_RANGE_START = AttributeKey.longKey("range.start");
  private static final AttributeKey<Long> ATTR_RANGE_END = AttributeKey.longKey("range.end");
  private static final AttributeKey<Long> ATTR_BYTES = AttributeKey.longKey("bytes");

  private final S3Client s3Client;
  private final String bucket;
  private final String key;
  private final long objectLength;
  private final MeterRegistry meterRegistry;
  private final Tracer tracer;

  private long position;
  private boolean closed;

  public S3RandomAccessReader(
      S3Client s3Client,
      String bucket,
      String key,
      long objectLength,
      MeterRegistry meterRegistry,
      Tracer tracer) {
    this.s3Client = s3Client;
    this.bucket = bucket;
    this.key = key;
    this.objectLength = objectLength;
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
  }

  @Override
  public void seek(long newPosition) {
    checkOpen();
    this.position = newPosition;
  }

  @Override
  public long getPosition() {
    checkOpen();
    return position;
  }

  @Override
  public long length() {
    return objectLength;
  }

  @Override
  public int readInt() throws IOException {
    byte[] buf = new byte[Integer.BYTES];
    readFully(buf);
    return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).getInt();
  }

  @Override
  public long readLong() throws IOException {
    byte[] buf = new byte[Long.BYTES];
    readFully(buf);
    return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).getLong();
  }

  @Override
  public float readFloat() throws IOException {
    return Float.intBitsToFloat(readInt());
  }

  @Override
  public void readFully(byte[] dst) throws IOException {
    readRange(position, dst, 0, dst.length);
    position += dst.length;
  }

  @Override
  public void readFully(ByteBuffer dst) throws IOException {
    int remaining = dst.remaining();
    if (remaining == 0) {
      return;
    }
    byte[] buf = new byte[remaining];
    readRange(position, buf, 0, remaining);
    dst.put(buf);
    position += remaining;
  }

  @Override
  public void readFully(long[] dst) throws IOException {
    byte[] buf = new byte[dst.length * Long.BYTES];
    readFully(buf);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < dst.length; i++) {
      dst[i] = bb.getLong();
    }
  }

  @Override
  public void read(int[] dst, int offset, int count) throws IOException {
    byte[] buf = new byte[count * Integer.BYTES];
    readFully(buf);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < count; i++) {
      dst[offset + i] = bb.getInt();
    }
  }

  @Override
  public void read(float[] dst, int offset, int count) throws IOException {
    byte[] buf = new byte[count * Float.BYTES];
    readFully(buf);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < count; i++) {
      dst[offset + i] = bb.getFloat();
    }
  }

  @Override
  public void close() {
    closed = true;
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("reader already closed (bucket=" + bucket + " key=" + key + ")");
    }
  }

  /**
   * Fetch {@code length} bytes starting at {@code startOffset} into
   * {@code dst[dstOffset..]}. Wraps the call in the standard span + meter
   * instrumentation. Visible for reuse by the block-cache decorator so its
   * cold-block fetches share the same observability envelope.
   */
  public void readRange(long startOffset, byte[] dst, int dstOffset, int length)
      throws IOException {
    checkOpen();
    if (length == 0) {
      return;
    }
    long endInclusive = startOffset + length - 1;
    Span span =
        tracer
            .spanBuilder(SPAN_RANGE_GET)
            .setAllAttributes(
                Attributes.of(
                    ATTR_BUCKET, bucket,
                    ATTR_KEY, key,
                    ATTR_RANGE_START, startOffset,
                    ATTR_RANGE_END, endInclusive))
            .startSpan();
    long startNanos = System.nanoTime();
    try (Scope ignored = span.makeCurrent()) {
      GetObjectRequest request =
          GetObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .range("bytes=" + startOffset + "-" + endInclusive)
              .build();
      try (ResponseInputStream<GetObjectResponse> stream =
          s3Client.getObject(request, ResponseTransformer.toInputStream())) {
        int filled = 0;
        while (filled < length) {
          int n = stream.read(dst, dstOffset + filled, length - filled);
          if (n < 0) {
            throw new IOException(
                "unexpected EOF reading s3://"
                    + bucket
                    + "/"
                    + key
                    + " bytes="
                    + startOffset
                    + "-"
                    + endInclusive
                    + " filled="
                    + filled);
          }
          filled += n;
        }
      }
      span.setAttribute(ATTR_BYTES, (long) length);
    } catch (IOException | RuntimeException e) {
      span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
      throw e;
    } finally {
      long elapsed = System.nanoTime() - startNanos;
      Timer.builder(METER_GET_DURATION)
          .tag(TAG_CACHE_HIT, "false")
          .register(meterRegistry)
          .record(elapsed, TimeUnit.NANOSECONDS);
      Counter.builder(METER_GET_BYTES)
          .tag(TAG_DIRECTION, "download")
          .baseUnit("bytes")
          .register(meterRegistry)
          .increment(length);
      span.end();
    }
  }
}
