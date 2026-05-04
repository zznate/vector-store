package io.github.zznate.vectorstore.app.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.app.resource.AbstractResourceTest;
import io.github.zznate.vectorstore.app.testprofile.L2DiskCacheProfile;
import io.github.zznate.vectorstore.app.testresource.MinioTestResource;
import io.github.zznate.vectorstore.storage.cache.BlockCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the L2 disk tier behind a real MinIO segment store.
 * Configured (via {@link L2DiskCacheProfile}) with a tiny L1 + L2 off-heap
 * so a modest workload spills past both into the disk tier within a
 * single test run, letting us assert {@code tier=l2_disk} hits actually
 * accumulate when blocks miss in upper tiers.
 *
 * <p>Read order under this profile: L1 heap (1 block) → L2 off-heap
 * (2 blocks) → L2 disk (16 MiB, plenty) → S3 / MinIO. The chain is
 * built by {@code BlockCacheProducer} when both tiers' {@code enabled}
 * flags are true.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
@TestProfile(L2DiskCacheProfile.class)
class L2DiskCacheIT extends AbstractResourceTest {

  private static final String BUCKET = DEMO_BUCKET;
  private static final String INDEX = "diskcache";
  private static final int DIM = 16;
  private static final long SEED = 2026_05_01L;

  @Inject MeterRegistry meterRegistry;
  @Inject BlockCache blockCache;

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
                + "\",\"displayName\":\"DiskCache\",\"dimension\":"
                + DIM
                + ",\"metric\":\"COSINE\",\"engineParams\":{}}")
        .when()
        .post("/v1/buckets/" + BUCKET + "/indexes")
        .then()
        .statusCode(201);
  }

  @Test
  void chainedL2IsConfiguredWithBothTiers() {
    // Sanity: producer wired the chain, not a single tier. Tier name comes
    // from ChainedL2Provider.TIER_NAME.
    assertThat(blockCache.l2()).isNotNull();
    assertThat(blockCache.l2().tierName()).isEqualTo("chained");
  }

  @Test
  void diskTierServesAfterUpperTiersChurn() {
    // Build a working set whose graph + sidecars span more 64 KiB blocks
    // than L1 (1 block) plus L2 off-heap (16 slots / 2 per shard) can
    // hold simultaneously. 8192 vectors push the segment well past the
    // off-heap budget; repeated queries then force evictions and the
    // disk tier serves the next reads.
    Random rng = new Random(SEED);
    List<VectorRow> rows = new ArrayList<>();
    for (int i = 0; i < 8192; i++) {
      rows.add(new VectorRow("v-" + i, randomUnit(rng)));
    }
    rows.add(new VectorRow("v-target", axisVector(0)));
    postVectors(rows);
    commit().then().statusCode(200);

    // Warm pass — populate the chain by reading every block at least once.
    float[] firstQuery = new float[DIM];
    firstQuery[0] = 1f;
    runQuery(firstQuery);

    double diskHitsBefore = diskHitCount();

    // Eviction pass — many queries against varying directions to evict
    // upper-tier blocks. With L1+L2 holding only ~17 blocks total against
    // a much larger working set, blocks beyond that must be re-fetched
    // and the disk tier serves any block we wrote during the warm pass.
    Random qRng = new Random(SEED + 1);
    int queries = 100;
    for (int i = 0; i < queries; i++) {
      runQuery(randomUnit(qRng));
    }

    double diskHitsAfter = diskHitCount();

    assertThat(diskHitsAfter)
        .as("L2 disk tier must serve at least one read once upper tiers churn")
        .isGreaterThan(diskHitsBefore);
  }

  // ------------------------------------------------------------------

  private double diskHitCount() {
    Counter c =
        meterRegistry
            .find("vectorstore.cache.hit")
            .tag("tier", "l2_disk")
            .tag("cache_name", "block")
            .counter();
    return c == null ? 0.0 : c.count();
  }

  private void runQuery(float[] vector) {
    Response r =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(queryBody(vector, 5))
            .when()
            .post(indexPath() + "/vectors:query");
    r.then().statusCode(200);
  }

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
}
