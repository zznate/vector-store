package io.github.zznate.vectorstore.storage.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Pure-unit verification of the S3 reader against a mocked {@link S3Client}.
 * The focus is on the {@code Range} header shape (offsets are inclusive on
 * both ends), endian decoding, and that the {@code cache_hit=false} /
 * {@code direction=download} meters are updated for every read.
 */
@ExtendWith(MockitoExtension.class)
class S3RandomAccessReaderTest {

  private static final String BUCKET = "vectorstore";
  private static final String KEY = "bucket/index/seg/graph.jvec";

  @Mock S3Client s3Client;

  MeterRegistry registry;
  Tracer tracer;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    tracer = TracerProvider.noop().get("test");
  }

  @Test
  void readIntDecodesBigEndian() throws Exception {
    byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x01020304).array();
    stubRangeResponse(payload);

    try (S3RandomAccessReader reader =
        new S3RandomAccessReader(s3Client, BUCKET, KEY, 1024L, registry, tracer)) {
      reader.seek(0);
      assertThat(reader.readInt()).isEqualTo(0x01020304);
      assertThat(reader.getPosition()).isEqualTo(4);
    }

    GetObjectRequest captured = captureRequest();
    assertThat(captured.range()).isEqualTo("bytes=0-3");
    assertThat(captured.bucket()).isEqualTo(BUCKET);
    assertThat(captured.key()).isEqualTo(KEY);
  }

  @Test
  void readFullyIssuesInclusiveRangeAtCurrentPosition() throws Exception {
    byte[] payload = new byte[16];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (0x80 + i);
    }
    stubRangeResponse(payload);

    byte[] out = new byte[16];
    try (S3RandomAccessReader reader =
        new S3RandomAccessReader(s3Client, BUCKET, KEY, 4096L, registry, tracer)) {
      reader.seek(32);
      reader.readFully(out);
    }

    assertThat(out).containsExactly(payload);
    assertThat(captureRequest().range()).isEqualTo("bytes=32-47");
  }

  @Test
  void readFloatDecodesBigEndianIeee754() throws Exception {
    float expected = 3.141592f;
    byte[] payload =
        ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(Float.floatToRawIntBits(expected))
            .array();
    stubRangeResponse(payload);

    try (S3RandomAccessReader reader =
        new S3RandomAccessReader(s3Client, BUCKET, KEY, 4L, registry, tracer)) {
      reader.seek(0);
      assertThat(reader.readFloat()).isEqualTo(expected);
    }
  }

  @Test
  void readFloatArrayDecodesNContiguousValues() throws Exception {
    float[] expected = {1.0f, -2.5f, 3.75f, 0.125f};
    ByteBuffer bb = ByteBuffer.allocate(expected.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
    for (float f : expected) {
      bb.putInt(Float.floatToRawIntBits(f));
    }
    stubRangeResponse(bb.array());

    float[] out = new float[expected.length];
    try (S3RandomAccessReader reader =
        new S3RandomAccessReader(s3Client, BUCKET, KEY, 4096L, registry, tracer)) {
      reader.seek(128);
      reader.read(out, 0, out.length);
    }

    assertThat(out).containsExactly(expected);
    assertThat(captureRequest().range())
        .isEqualTo("bytes=128-" + (128 + expected.length * Float.BYTES - 1));
  }

  @Test
  void metersRecordOneDownloadPerRangedGet() throws Exception {
    stubRangeResponse(new byte[8]);
    try (S3RandomAccessReader reader =
        new S3RandomAccessReader(s3Client, BUCKET, KEY, 1024L, registry, tracer)) {
      reader.seek(0);
      reader.readLong();
    }

    double downloadedBytes =
        registry.counter("vectorstore.storage.get.bytes", "direction", "download").count();
    long getDurationCount =
        registry.timer("vectorstore.storage.get.duration", "cache_hit", "false").count();

    assertThat(downloadedBytes).isEqualTo(8.0);
    assertThat(getDurationCount).isEqualTo(1L);
  }

  @Test
  void closeMakesSubsequentReadsFail() {
    S3RandomAccessReader reader =
        new S3RandomAccessReader(s3Client, BUCKET, KEY, 1024L, registry, tracer);
    reader.close();
    org.assertj.core.api.Assertions.assertThatThrownBy(reader::readInt)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already closed");
  }

  @SuppressWarnings("unchecked")
  private void stubRangeResponse(byte[] payload) {
    ResponseInputStream<GetObjectResponse> ris =
        new ResponseInputStream<>(
            GetObjectResponse.builder().contentLength((long) payload.length).build(),
            new ByteArrayInputStream(payload));
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(ris);
  }

  @SuppressWarnings("unchecked")
  private GetObjectRequest captureRequest() {
    ArgumentCaptor<GetObjectRequest> cap = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client).getObject(cap.capture(), any(ResponseTransformer.class));
    return cap.getValue();
  }
}
