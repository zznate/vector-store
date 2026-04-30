package io.github.zznate.vectorstore.app.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.app.resource.AbstractResourceTest;
import io.github.zznate.vectorstore.app.testresource.MinioTestResource;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.retention.RetentionSweep;
import io.github.zznate.vectorstore.storage.config.StorageConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

/**
 * End-to-end retention sweep IT against MinIO. Walks the full lifecycle:
 * create bucket + index, ingest + commit (so segments + manifest exist on
 * object storage), DELETE via REST (soft-delete), force the catalog
 * deleted_at far enough in the past that retention has expired, invoke
 * {@link RetentionSweep#runOnce()}, and assert hard-delete cascaded all
 * the way to S3.
 *
 * <p>The Quarkus {@code @Scheduled} binding is not exercised here — the
 * scheduler is gated off in tests by {@code application.properties}, so
 * the IT calls {@code runOnce()} directly. A separate slice could add a
 * scheduler-on test if the timer wiring itself needs IT coverage.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class RetentionSweepIT extends AbstractResourceTest {

  private static final String BUCKET = DEMO_BUCKET;
  private static final String INDEX = "lifecycle";
  private static final int DIM = 16;

  @Inject RetentionSweep sweep;
  @Inject BucketRepository buckets;
  @Inject VectorIndexRepository indexes;
  @Inject SegmentRepository segments;
  @Inject ManifestVersionRepository manifests;
  @Inject Jdbi jdbi;
  @Inject S3Client s3Client;
  @Inject StorageConfig storageConfig;

  @Test
  void hardDeletesIndexAndCascadesToObjectStoreAfterRetention() {
    seedBucketAndIndex();
    String segmentPrefix = ingestAndCommitSegment();

    // Sanity: object-store data is there before sweep.
    assertObjectsUnderPrefix(segmentPrefix).isNotEmpty();

    // Soft-delete via REST (records deleted_at = now()).
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .delete("/v1/buckets/" + BUCKET + "/indexes/" + INDEX)
        .then()
        .statusCode(204);
    assertThat(indexes.findIncludingDeleted(qualifiedIndexId()))
        .hasValueSatisfying(idx -> assertThat(idx.isDeleted()).isTrue());

    // Backdate the deletion so retention has elapsed. The retention
    // window default is 7d; pushing 30d into the past is well past.
    Instant past = Instant.now().minus(java.time.Duration.ofDays(30));
    backdateIndexDeletedAt(qualifiedIndexId(), past);

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.indexesHardDeleted()).isEqualTo(1);
    assertThat(indexes.findIncludingDeleted(qualifiedIndexId())).isEmpty();
    assertThat(segments.listByIndex(qualifiedIndexId())).isEmpty();
    assertThat(manifests.listByIndex(qualifiedIndexId())).isEmpty();
    assertObjectsUnderPrefix(segmentPrefix)
        .as("segment object-store prefix must be cleaned up")
        .isEmpty();
  }

  @Test
  void bucketHardDeleteWaitsForChildIndexes() {
    seedBucketAndIndex();
    Instant past = Instant.now().minus(java.time.Duration.ofDays(30));

    // Soft-delete bucket only (children remain active).
    buckets.softDelete(BUCKET, Instant.now());
    backdateBucketDeletedAt(BUCKET, past);

    RetentionSweep.SweepResult result = sweep.runOnce();

    assertThat(result.bucketsHardDeleted())
        .as("bucket cannot hard-delete while active child indexes exist")
        .isZero();
    assertThat(buckets.findIncludingDeleted(BUCKET)).isPresent();
  }

  @Test
  void disabledSweepIsNoop() {
    // RetentionSweep itself does not check `enabled`; the scheduler does.
    // This test asserts that a manually-invoked sweep on an empty
    // catalog returns the zero result without error.
    assertThat(sweep.runOnce()).isEqualTo(new RetentionSweep.SweepResult(0, 0));
  }

  // ---- helpers --------------------------------------------------------

  private void seedBucketAndIndex() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"bucketId\":\"" + BUCKET + "\",\"displayName\":\"Demo\"}")
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(201);

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            "{\"indexId\":\""
                + INDEX
                + "\",\"displayName\":\"Lifecycle\",\"dimension\":"
                + DIM
                + ",\"metric\":\"COSINE\",\"engineParams\":{}}")
        .when()
        .post("/v1/buckets/" + BUCKET + "/indexes")
        .then()
        .statusCode(201);
  }

  private String ingestAndCommitSegment() {
    Random rng = new Random(42);
    List<String> ids = new ArrayList<>();
    StringBuilder sb = new StringBuilder("{\"vectors\":[");
    for (int i = 0; i < 64; i++) {
      String id = "v-" + i;
      ids.add(id);
      if (i > 0) {
        sb.append(",");
      }
      sb.append("{\"id\":\"").append(id).append("\",\"vector\":[");
      double norm = 0;
      float[] v = new float[DIM];
      for (int j = 0; j < DIM; j++) {
        v[j] = (float) rng.nextGaussian();
        norm += v[j] * v[j];
      }
      norm = Math.sqrt(norm);
      for (int j = 0; j < DIM; j++) {
        if (j > 0) {
          sb.append(",");
        }
        sb.append(v[j] / norm);
      }
      sb.append("],\"attributes\":{}}");
    }
    sb.append("]}");
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(sb.toString())
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + "/vectors:put")
        .then()
        .statusCode(202);
    Response commit =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .when()
            .post("/v1/indexes/" + BUCKET + "/" + INDEX + ":commit");
    commit.then().statusCode(200);
    String segmentId = commit.jsonPath().getString("segmentId");
    return qualifiedIndexId() + "/" + segmentId;
  }

  private void backdateIndexDeletedAt(String indexId, Instant past) {
    jdbi.useHandle(
        h ->
            h.createUpdate(
                    "UPDATE vector_index SET deleted_at = :past WHERE index_id = :indexId")
                .bind("past", past)
                .bind("indexId", indexId)
                .execute());
  }

  private void backdateBucketDeletedAt(String bucketId, Instant past) {
    jdbi.useHandle(
        h ->
            h.createUpdate(
                    "UPDATE vector_bucket SET deleted_at = :past WHERE bucket_id = :bucketId")
                .bind("past", past)
                .bind("bucketId", bucketId)
                .execute());
  }

  private org.assertj.core.api.AbstractListAssert<?, List<? extends String>, String, ?>
      assertObjectsUnderPrefix(String prefix) {
    ListObjectsV2Response listed =
        s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(storageConfig.bucket())
                .prefix(prefix + "/")
                .build());
    return assertThat(listed.contents().stream().map(c -> c.key()).toList());
  }

  private String qualifiedIndexId() {
    return BUCKET + "/" + INDEX;
  }
}
