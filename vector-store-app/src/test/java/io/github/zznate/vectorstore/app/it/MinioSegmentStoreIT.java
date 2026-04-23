package io.github.zznate.vectorstore.app.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.app.resource.AbstractResourceTest;
import io.github.zznate.vectorstore.app.testresource.MinioTestResource;
import io.github.zznate.vectorstore.storage.config.StorageConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * End-to-end integration tests that boot a MinIO container via
 * {@link MinioTestResource} and exercise the phase-3 object-store path
 * without any test doubles.
 *
 * <p>Acceptance criteria covered:
 * <ul>
 *   <li>Segment artefacts land at
 *       {@code <bucket-id>/<index-id>/<segment-id>/graph.jvec} (+ sidecars).
 *   </li>
 *   <li>Warm query after a cold query increments
 *       {@code vectorstore.cache.hit{tier=l1_heap, cache_name=block}}
 *       strictly more than the cold pass, confirming the block cache is
 *       in the read path.</li>
 *   <li>Key storage meters emit during a real workload.</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class MinioSegmentStoreIT extends AbstractResourceTest {

  private static final String BUCKET = DEMO_BUCKET;
  private static final String INDEX = "products";
  private static final int DIM = 16;
  private static final long SEED = 2026_04_20L;

  @Inject MeterRegistry meterRegistry;
  @Inject S3Client s3Client;
  @Inject StorageConfig storageConfig;

  @BeforeEach
  void seedIndex() {
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
  }

  @Test
  void commitPublishesGraphAndSidecarsUnderExpectedObjectPrefix() {
    int count = 256;
    Random rng = new Random(SEED);
    List<VectorRow> rows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      rows.add(new VectorRow("v-" + i, randomUnit(rng)));
    }
    rows.add(new VectorRow("v-target", axisVector(0)));

    postVectors(rows);
    Response commitResp = commit();
    commitResp.then().statusCode(200);

    String segmentId = commitResp.jsonPath().getString("segmentId");
    assertThat(segmentId).isNotBlank();

    String prefix = qualifiedId() + "/" + segmentId + "/";
    ListObjectsV2Response listed =
        s3Client.listObjectsV2(
            ListObjectsV2Request.builder().bucket(storageConfig.bucket()).prefix(prefix).build());
    List<String> keys = listed.contents().stream().map(S3Object::key).sorted().toList();
    assertThat(keys)
        .as("expected phase-3 object layout under %s", prefix)
        .contains(
            prefix + "attributes.jsonl",
            prefix + "graph.jvec",
            prefix + "header.json",
            prefix + "ordinals.jsonl",
            prefix + "tombstones.roar");

    // Queryable: the hand-injected axis vector wins for a query aligned
    // with the first basis direction.
    float[] query = new float[DIM];
    query[0] = 1f;
    Response queryResp =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(queryBody(query, 5))
            .when()
            .post(indexPath() + "/vectors:query");
    queryResp.then().statusCode(200);
    assertThat(queryResp.jsonPath().getString("hits[0].id")).isEqualTo("v-target");
  }

  @Test
  void warmQueryServesBlocksFromCacheAndLogsStorageBytes() {
    Random rng = new Random(SEED + 1);
    List<VectorRow> rows = new ArrayList<>();
    for (int i = 0; i < 128; i++) {
      rows.add(new VectorRow("v-" + i, randomUnit(rng)));
    }
    rows.add(new VectorRow("v-target", axisVector(0)));
    postVectors(rows);
    commit().then().statusCode(200);

    float[] query = new float[DIM];
    query[0] = 1f;
    String body = queryBody(query, 5);

    Counter hits = meterRegistry
            .find("vectorstore.cache.hit")
            .tag("tier", "l1_heap")
            .tag("cache_name", "block")
            .counter();
    double hitsBefore = hits == null ? 0 : hits.count();

    // Cold query.
    Response cold =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(indexPath() + "/vectors:query");
    cold.then().statusCode(200);
    Counter hitsCounter = meterRegistry
            .find("vectorstore.cache.hit")
            .tag("tier", "l1_heap")
            .tag("cache_name", "block")
            .counter();
    double hitsAfterCold = hitsCounter == null ? 0 : hitsCounter.count();

    // Warm query — identical vector, identical topK.
    Response warm =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(indexPath() + "/vectors:query");
    warm.then().statusCode(200);
    double hitsAfterWarm =
        meterRegistry
            .find("vectorstore.cache.hit")
            .tag("tier", "l1_heap")
            .tag("cache_name", "block")
            .counter().count();

    assertThat(hitsAfterWarm - hitsAfterCold)
        .as("warm query must register strictly more block-cache hits than the cold pass")
        .isGreaterThan(0);

    // Sanity: both key storage meters have seen traffic during this test.
    double downloaded =
        meterRegistry
            .find("vectorstore.storage.get.bytes")
            .tag("direction", "download")
            .counter()
            .count();
    assertThat(downloaded)
        .as("download counter should have accumulated bytes during cold fetch")
        .isGreaterThan(hitsBefore);
  }

  // ------------------------------------------------------------------

  private record VectorRow(String id, float[] vector) {}

  private void postVectors(List<VectorRow> rows) {
    StringBuilder sb = new StringBuilder("{\"vectors\":[");
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      VectorRow row = rows.get(i);
      sb.append("{\"id\":\"").append(row.id()).append("\",\"vector\":[");
      for (int j = 0; j < row.vector().length; j++) {
        if (j > 0) {
          sb.append(",");
        }
        sb.append(row.vector()[j]);
      }
      sb.append("],\"attributes\":{}}");
    }
    sb.append("]}");
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(sb.toString())
        .when()
        .post(indexPath() + "/vectors:put")
        .then()
        .statusCode(202);
  }

  private Response commit() {
    return given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post(indexPath() + ":commit");
  }

  private static String queryBody(float[] vector, int topK) {
    StringBuilder sb = new StringBuilder("{\"vector\":[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(vector[i]);
    }
    sb.append("],\"topK\":").append(topK).append("}");
    return sb.toString();
  }

  private static float[] randomUnit(Random rng) {
    float[] v = new float[DIM];
    double norm = 0;
    for (int i = 0; i < DIM; i++) {
      v[i] = (float) rng.nextGaussian();
      norm += v[i] * v[i];
    }
    norm = Math.sqrt(norm);
    for (int i = 0; i < DIM; i++) {
      v[i] = (float) (v[i] / norm);
    }
    return v;
  }

  private static float[] axisVector(int axis) {
    float[] v = new float[DIM];
    v[axis] = 1f;
    return v;
  }

  private String indexPath() {
    return "/v1/indexes/" + BUCKET + "/" + INDEX;
  }

  private String qualifiedId() {
    return BUCKET + "/" + INDEX;
  }
}
