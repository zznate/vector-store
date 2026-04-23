package io.github.zznate.vectorstore.app.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.app.resource.AbstractResourceTest;
import io.github.zznate.vectorstore.app.testprofile.NonexistentBucketProfile;
import io.github.zznate.vectorstore.app.testresource.MinioTestResource;
import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers acceptance criterion 6: a simulated upload failure during commit
 * leaves the segment in a non-{@code ACTIVE} terminal state and does not
 * advance the manifest version. A real MinIO is running (so the S3 client
 * can reach a real endpoint) but the configured bucket does not exist, so
 * every {@code PutObject} fails with {@code NoSuchBucket}.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
@TestProfile(NonexistentBucketProfile.class)
class CommitResilienceIT extends AbstractResourceTest {

  private static final String BUCKET = DEMO_BUCKET;
  private static final String INDEX = "products";
  private static final int DIM = 4;

  @Inject StagedTombstoneRepository stagedRepo;

  @Test
  void uploadFailureLeavesSegmentNonActiveAndManifestUnadvanced() {
    // Seed bucket + index via the admin API.
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
                + "\",\"displayName\":\"Products\",\"dimension\":"
                + DIM
                + ",\"metric\":\"COSINE\",\"engineParams\":{}}")
        .when()
        .post("/v1/buckets/" + BUCKET + "/indexes")
        .then()
        .statusCode(201);

    // PUT a handful of vectors so the commit has something to upload.
    String putBody =
        "{\"vectors\":["
            + "{\"id\":\"a\",\"vector\":[1,0,0,0],\"attributes\":{}},"
            + "{\"id\":\"b\",\"vector\":[0,1,0,0],\"attributes\":{}}"
            + "]}";
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(putBody)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + "/vectors:put")
        .then()
        .statusCode(202);

    // Commit must surface a 5xx because the target bucket does not exist.
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + ":commit")
        .then()
        .statusCode(500);

    // Catalog invariants: the segment row exists but is not ACTIVE, and no
    // manifest version row was appended for this index. index_id stores the
    // qualified "<bucket>/<index>" form (see VectorIndexDao).
    String qualifiedIndexId = BUCKET + "/" + INDEX;

    List<String> segmentStates =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        "SELECT state FROM segment WHERE index_id = :indexId")
                    .bind("indexId", qualifiedIndexId)
                    .mapTo(String.class)
                    .list());
    assertThat(segmentStates)
        .as("commit rollback must leave every segment row in a non-ACTIVE state")
        .isNotEmpty()
        .allSatisfy(state -> assertThat(state).isNotEqualTo("ACTIVE"));

    Integer manifestVersionCount =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        "SELECT COUNT(*) FROM manifest_version WHERE index_id = :indexId")
                    .bind("indexId", qualifiedIndexId)
                    .mapTo(Integer.class)
                    .one());
    assertThat(manifestVersionCount)
        .as("failed commit must not advance manifest_version")
        .isZero();
  }

  @Test
  void uploadFailureLeavesStagedTombstonesIntactForRetry() {
    // Seed bucket + index.
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
                + "\",\"displayName\":\"Products\",\"dimension\":"
                + DIM
                + ",\"metric\":\"COSINE\",\"engineParams\":{}}")
        .when()
        .post("/v1/buckets/" + BUCKET + "/indexes")
        .then()
        .statusCode(201);

    // Stage some deletes, then PUT enough vectors that commit will try to
    // upload (and fail because the bucket does not exist).
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"ids\":[\"dead-1\",\"dead-2\",\"dead-3\"]}")
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + "/vectors:delete")
        .then()
        .statusCode(204);

    String qualifiedIndexId = BUCKET + "/" + INDEX;
    assertThat(stagedRepo.snapshot(qualifiedIndexId)).hasSize(3);

    String putBody =
        "{\"vectors\":[{\"id\":\"a\",\"vector\":[1,0,0,0],\"attributes\":{}}]}";
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(putBody)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + "/vectors:put")
        .then()
        .statusCode(202);

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + ":commit")
        .then()
        .statusCode(500);

    // The commit transaction never ran. Staging rows persist untouched,
    // ready for the next (successful) commit attempt.
    assertThat(stagedRepo.snapshot(qualifiedIndexId))
        .as("publish-phase failure must not touch staging")
        .containsExactlyInAnyOrder("dead-1", "dead-2", "dead-3");
    assertThat(stagedRepo.count(qualifiedIndexId)).isEqualTo(3);
  }
}
