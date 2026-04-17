package io.github.zznate.vectorstore.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link VectorStoreException} hierarchy properties that drive
 * {@link VectorStoreExceptionMapper}. The mapper itself is exercised
 * end-to-end by the {@code @QuarkusTest} component tests in
 * {@code vector-store-app}; bringing a full JAX-RS runtime into this module's
 * unit classpath is not worth the weight.
 */
class VectorStoreExceptionMapperTest {

  @Test
  void bucketNotFoundCarriesNotFoundStatusAndStableCode() {
    BucketNotFoundException ex = new BucketNotFoundException("demo");

    assertThat(ex.status()).isEqualTo(Status.NOT_FOUND);
    assertThat(ex.errorCode()).isEqualTo("bucket_not_found");
    assertThat(ex.getMessage()).isEqualTo("Bucket not found: demo");
  }

  @Test
  void indexNotFoundCarriesNotFoundStatusAndStableCode() {
    IndexNotFoundException ex = new IndexNotFoundException("demo/products");

    assertThat(ex.status()).isEqualTo(Status.NOT_FOUND);
    assertThat(ex.errorCode()).isEqualTo("index_not_found");
    assertThat(ex.getMessage()).isEqualTo("Index not found: demo/products");
  }

  @Test
  void bucketAlreadyExistsCarriesConflict() {
    BucketAlreadyExistsException ex = new BucketAlreadyExistsException("demo");

    assertThat(ex.status()).isEqualTo(Status.CONFLICT);
    assertThat(ex.errorCode()).isEqualTo("bucket_already_exists");
  }

  @Test
  void indexAlreadyExistsCarriesConflict() {
    IndexAlreadyExistsException ex = new IndexAlreadyExistsException("demo/x");

    assertThat(ex.status()).isEqualTo(Status.CONFLICT);
    assertThat(ex.errorCode()).isEqualTo("index_already_exists");
  }

  @Test
  void bucketNotEmptyCarriesConflict() {
    BucketNotEmptyException ex = new BucketNotEmptyException("demo");

    assertThat(ex.status()).isEqualTo(Status.CONFLICT);
    assertThat(ex.errorCode()).isEqualTo("bucket_not_empty");
    assertThat(ex.getMessage()).contains("demo");
  }

  @Test
  void notImplementedFormatsPromptNumberAndOperation() {
    NotImplementedException ex = new NotImplementedException("vectors:put", 2);

    assertThat(ex.status()).isEqualTo(Status.NOT_IMPLEMENTED);
    assertThat(ex.errorCode()).isEqualTo("not_implemented");
    assertThat(ex.getMessage()).isEqualTo("vectors:put lands in prompt 02");
  }

  @Test
  void unauthorizedCarries401() {
    UnauthorizedException ex = new UnauthorizedException("missing api key");

    assertThat(ex.status()).isEqualTo(Status.UNAUTHORIZED);
    assertThat(ex.errorCode()).isEqualTo("unauthorized");
  }

  @Test
  void forbiddenCarries403() {
    ForbiddenException ex = new ForbiddenException("bucket scope mismatch");

    assertThat(ex.status()).isEqualTo(Status.FORBIDDEN);
    assertThat(ex.errorCode()).isEqualTo("forbidden");
  }
}
