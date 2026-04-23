package io.github.zznate.vectorstore.app.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.app.resource.AbstractResourceTest;
import io.github.zznate.vectorstore.app.testresource.MinioTestResource;
import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase-5 behaviour: uncommitted {@code :delete} requests persist to the
 * {@code staged_tombstone} catalog table and survive process restart, so
 * deletes are never silently dropped between delete and commit. Inspecting
 * the catalog directly is equivalent to observing it from a freshly-started
 * JVM — the previous in-memory staging set was the restart-loss hazard, and
 * the new impl keeps nothing in memory.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class StagedTombstoneDurabilityIT extends AbstractResourceTest {

  private static final String BUCKET = DEMO_BUCKET;
  private static final int DIM = 16;

  @Inject StagedTombstoneRepository stagedRepo;

  @BeforeEach
  void seedBucket() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"bucketId\":\"" + BUCKET + "\",\"displayName\":\"Demo\"}")
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(201);
  }

  @Test
  void deleteRequestsPersistToCatalogAndDrainTransactionallyOnCommit() {
    String indexName = "durable-staging";
    createIndex(indexName);

    int total = 100;
    Random rng = new Random(2026_04_23L);
    StringBuilder body = new StringBuilder("{\"vectors\":[");
    for (int i = 0; i < total; i++) {
      float[] v = randomUnit(rng);
      if (i > 0) {
        body.append(",");
      }
      body.append("{\"id\":\"v-").append(i).append("\",\"vector\":[");
      for (int j = 0; j < DIM; j++) {
        if (j > 0) {
          body.append(",");
        }
        body.append(v[j]);
      }
      body.append("],\"attributes\":{}}");
    }
    body.append("]}");
    putVectors(indexName, body.toString());
    commit(indexName).then().statusCode(200);

    // Delete the first 20 ids. These land in staging but not yet in any
    // segment's tombstones.roar sidecar.
    StringBuilder ids = new StringBuilder("{\"ids\":[");
    for (int i = 0; i < 20; i++) {
      if (i > 0) {
        ids.append(",");
      }
      ids.append("\"v-").append(i).append("\"");
    }
    ids.append("]}");
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(ids.toString())
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + "/vectors:delete")
        .then()
        .statusCode(204);

    // Catalog inspection — this is exactly what a freshly-started JVM would
    // see on boot. Proves the staging set is durable, not in-memory.
    String qualifiedIndexId = BUCKET + "/" + indexName;
    Set<String> persisted = stagedRepo.snapshot(qualifiedIndexId);
    assertThat(persisted)
        .as("delete request must persist to staged_tombstone rows")
        .hasSize(20)
        .contains("v-0", "v-19");
    assertThat(stagedRepo.count(qualifiedIndexId)).isEqualTo(20);
    assertThat(stagedRepo.isStaged(qualifiedIndexId, "v-0")).isTrue();
    assertThat(stagedRepo.isStaged(qualifiedIndexId, "v-99")).isFalse();

    // PUT a small second batch so the commit has something to flush. A
    // pure-delete commit is currently rejected by EmptyCommitException —
    // staging drains at the next commit that carries buffered vectors.
    StringBuilder second = new StringBuilder("{\"vectors\":[");
    for (int i = 0; i < 5; i++) {
      float[] v = randomUnit(rng);
      if (i > 0) {
        second.append(",");
      }
      second.append("{\"id\":\"w-").append(i).append("\",\"vector\":[");
      for (int j = 0; j < DIM; j++) {
        if (j > 0) {
          second.append(",");
        }
        second.append(v[j]);
      }
      second.append("],\"attributes\":{}}");
    }
    second.append("]}");
    putVectors(indexName, second.toString());

    // A successful commit drains the staging set in the same transaction as
    // the manifest append.
    commit(indexName).then().statusCode(200);
    assertThat(stagedRepo.snapshot(qualifiedIndexId))
        .as("successful commit must clear staging atomically")
        .isEmpty();
    assertThat(stagedRepo.count(qualifiedIndexId)).isZero();

    // Queries exclude deleted IDs — large topK to sweep the whole index.
    Response q = query(indexName, randomUnit(new Random(7L)), 200);
    q.then().statusCode(200);
    List<String> hitIds = q.jsonPath().getList("hits.id");
    for (int i = 0; i < 20; i++) {
      assertThat(hitIds).doesNotContain("v-" + i);
    }
  }

  @Test
  void repeatedDeleteOfSameIdIsIdempotent() {
    String indexName = "idempotent-staging";
    createIndex(indexName);
    String qualifiedIndexId = BUCKET + "/" + indexName;

    // Two overlapping delete requests; INSERT OR IGNORE keeps the staged
    // set as a simple set, not a multiset.
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"ids\":[\"v-1\",\"v-2\",\"v-3\"]}")
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + "/vectors:delete")
        .then()
        .statusCode(204);
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"ids\":[\"v-2\",\"v-3\",\"v-4\"]}")
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + "/vectors:delete")
        .then()
        .statusCode(204);

    assertThat(stagedRepo.snapshot(qualifiedIndexId))
        .containsExactlyInAnyOrder("v-1", "v-2", "v-3", "v-4");
    assertThat(stagedRepo.count(qualifiedIndexId)).isEqualTo(4);
  }

  @Test
  void indexDeletionCascadesStagingRows() {
    String indexName = "cascade-staging";
    createIndex(indexName);
    String qualifiedIndexId = BUCKET + "/" + indexName;

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"ids\":[\"v-1\",\"v-2\"]}")
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + "/vectors:delete")
        .then()
        .statusCode(204);
    assertThat(stagedRepo.count(qualifiedIndexId)).isEqualTo(2);

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .delete("/v1/buckets/" + BUCKET + "/indexes/" + indexName)
        .then()
        .statusCode(204);

    assertThat(stagedRepo.count(qualifiedIndexId))
        .as("ON DELETE CASCADE clears staging rows alongside the index")
        .isZero();
  }

  // ------------------------------------------------------------------

  private void createIndex(String indexName) {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            "{\"indexId\":\""
                + indexName
                + "\",\"displayName\":\""
                + indexName
                + "\",\"dimension\":"
                + DIM
                + ",\"metric\":\"COSINE\",\"engineParams\":{}}")
        .when()
        .post("/v1/buckets/" + BUCKET + "/indexes")
        .then()
        .statusCode(201);
  }

  private void putVectors(String indexName, String body) {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + "/vectors:put")
        .then()
        .statusCode(202);
  }

  private Response commit(String indexName) {
    return given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + ":commit");
  }

  private Response query(String indexName, float[] vector, int topK) {
    StringBuilder body = new StringBuilder("{\"vector\":[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        body.append(",");
      }
      body.append(vector[i]);
    }
    body.append("],\"topK\":").append(topK).append("}");
    return given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(body.toString())
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + indexName + "/vectors:query");
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
}
