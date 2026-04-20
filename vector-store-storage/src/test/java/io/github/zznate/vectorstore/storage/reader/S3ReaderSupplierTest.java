package io.github.zznate.vectorstore.storage.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@ExtendWith(MockitoExtension.class)
class S3ReaderSupplierTest {

  @Mock S3Client s3Client;

  @Test
  void constructorProbesObjectLengthViaHeadObject() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentLength(4096L).build());

    S3ReaderSupplier supplier =
        new S3ReaderSupplier(
            s3Client,
            "vectorstore",
            "b/i/s/graph.jvec",
            new SimpleMeterRegistry(),
            TracerProvider.noop().get("test"));

    assertThat(supplier.objectLength()).isEqualTo(4096L);

    ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
    verify(s3Client).headObject(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo("vectorstore");
    assertThat(captor.getValue().key()).isEqualTo("b/i/s/graph.jvec");
  }

  @Test
  void getReturnsFreshReadersBoundToSameObject() throws Exception {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentLength(1024L).build());

    S3ReaderSupplier supplier =
        new S3ReaderSupplier(
            s3Client,
            "vectorstore",
            "b/i/s/graph.jvec",
            new SimpleMeterRegistry(),
            TracerProvider.noop().get("test"));

    RandomAccessReader first = supplier.get();
    RandomAccessReader second = supplier.get();

    assertThat(first).isNotSameAs(second);
    assertThat(first.length()).isEqualTo(1024L);
    assertThat(second.length()).isEqualTo(1024L);

    first.close();
    second.close();
    supplier.close();
  }
}
