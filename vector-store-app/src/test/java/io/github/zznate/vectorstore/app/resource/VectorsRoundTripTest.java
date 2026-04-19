package io.github.zznate.vectorstore.app.resource;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end component tests for the phase 2 data plane: put -&gt; commit
 * -&gt; query, second commit captures only post-snapshot vectors,
 * delete-then-query, query against an index with no commits.
 *
 * <p>Every test creates a fresh {@code demo/products} index so the
 * catalog state is predictable. Segment files accumulate under
 * {@code target/test-segments/} across the suite; {@code mvn clean}
 * removes them.
 */
@QuarkusTest
class VectorsRoundTripTest extends AbstractResourceTest {

  private static final String BUCKET = DEMO_BUCKET;
  private static final String INDEX = "products";
  private static final int DIM = 4;
  private static final long SEED = 2026_04_20L;

  @Inject MeterRegistry meterRegistry;

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
  void fullRoundTripPutCommitQuery() {
    int count = 500;
    Random rng = new Random(SEED);
    List<VectorRow> rows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      rows.add(new VectorRow("v-" + i, randomUnit(rng)));
    }
    // Hand-injected ground-truth neighbour: the unit vector along x.
    VectorRow target = new VectorRow("v-target", new float[] {1f, 0f, 0f, 0f});
    rows.add(target);

    postVectors(rows);

    Timer commitTimerBefore =
        meterRegistry.find("vectorstore.commit.duration").tag("phase", "serialize").timer();
    long commitCountBefore = commitTimerBefore == null ? 0 : commitTimerBefore.count();

    Response commitResponse =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .when()
            .post(indexPath() + ":commit");
    commitResponse.then().statusCode(200);
    long vectorCount = commitResponse.jsonPath().getLong("vectorCount");
    int manifestVersion = commitResponse.jsonPath().getInt("manifestVersion");
    assertThat(vectorCount).isEqualTo(count + 1);
    assertThat(manifestVersion).isEqualTo(1);

    // Query with a vector very close to the target.
    float[] queryVector = {0.99f, 0.05f, 0.05f, 0.05f};
    String body = queryBody(queryVector, 5);
    Response queryResponse =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(indexPath() + "/vectors:query");
    queryResponse.then().statusCode(200);
    String topHitId = queryResponse.jsonPath().getString("hits[0].id");
    assertThat(topHitId).isEqualTo("v-target");

    // Metrics assertions. Both timers must have at least one more sample
    // than we observed before this test's commit + query.
    Timer commitTimerAfter =
        meterRegistry.find("vectorstore.commit.duration").tag("phase", "serialize").timer();
    assertThat(commitTimerAfter).isNotNull();
    assertThat(commitTimerAfter.count()).isGreaterThan(commitCountBefore);

    Timer queryTimer =
        meterRegistry.find("vectorstore.query.duration").tag("index_id", qualifiedId()).timer();
    assertThat(queryTimer).isNotNull();
    assertThat(queryTimer.count()).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void secondCommitCapturesOnlyPostSnapshotVectors() {
    // First batch -> first commit.
    List<VectorRow> first = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      first.add(new VectorRow("first-" + i, unitVector(i, DIM)));
    }
    postVectors(first);
    int firstCount =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .when()
            .post(indexPath() + ":commit")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getInt("vectorCount");
    assertThat(firstCount).isEqualTo(first.size());

    // Second batch -> second commit. Must reflect only the fresh batch.
    List<VectorRow> second = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      second.add(new VectorRow("second-" + i, unitVector(i + 100, DIM)));
    }
    postVectors(second);
    Response secondResponse =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .when()
            .post(indexPath() + ":commit");
    secondResponse.then().statusCode(200);
    assertThat(secondResponse.jsonPath().getInt("vectorCount")).isEqualTo(second.size());
    assertThat(secondResponse.jsonPath().getInt("manifestVersion")).isEqualTo(2);

    // Stats should now reflect both segments.
    Response stats =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .when()
            .get(indexPath() + "/stats");
    stats.then().statusCode(200);
    assertThat(stats.jsonPath().getInt("segmentCount")).isEqualTo(2);
    assertThat(stats.jsonPath().getLong("vectorCount")).isEqualTo(first.size() + second.size());
  }

  @Test
  void deleteThenQueryExcludesDeletedIds() {
    List<VectorRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(new VectorRow("id-" + i, unitVector(i, DIM)));
    }
    postVectors(rows);
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post(indexPath() + ":commit")
        .then()
        .statusCode(200);

    // Tombstone three IDs.
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"ids\":[\"id-0\",\"id-1\",\"id-2\"]}")
        .when()
        .post(indexPath() + "/vectors:delete")
        .then()
        .statusCode(204);

    // Query with topK=10; none of id-0 / id-1 / id-2 should appear.
    Response queryResponse =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(queryBody(unitVector(0, DIM), 10))
            .when()
            .post(indexPath() + "/vectors:query");
    queryResponse.then().statusCode(200);
    List<String> returnedIds = queryResponse.jsonPath().getList("hits.id");
    assertThat(returnedIds).doesNotContain("id-0", "id-1", "id-2");

    // Bookkeeping sanity: the tombstoned IDs also read as not-found via GET.
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get(indexPath() + "/vectors/id-0")
        .then()
        .statusCode(200)
        .body("id", is("id-0"))
        .body("found", is(false));
  }

  @Test
  void queryOnIndexWithNoCommitsReturnsEmptyHits() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(queryBody(unitVector(0, DIM), 10))
        .when()
        .post(indexPath() + "/vectors:query")
        .then()
        .statusCode(200)
        .body("hits", empty());

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get(indexPath() + "/stats")
        .then()
        .statusCode(200)
        .body("segmentCount", is(0))
        .body("vectorCount", is(0))
        .body("pendingVectorCount", greaterThanOrEqualTo(0));
  }

  // ------------------------------------------------------------------
  // helpers

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

  private static float[] unitVector(int index, int dim) {
    float[] v = new float[dim];
    v[index % dim] = 1f;
    return v;
  }

  private String indexPath() {
    return "/v1/indexes/" + BUCKET + "/" + INDEX;
  }

  private String qualifiedId() {
    return BUCKET + "/" + INDEX;
  }
}
