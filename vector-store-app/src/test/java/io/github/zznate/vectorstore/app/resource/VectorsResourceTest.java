package io.github.zznate.vectorstore.app.resource;

import static io.restassured.RestAssured.given;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Keeps coverage of the vector-level surface's auth + scope enforcement
 * after the phase 2 implementations replaced the earlier 501 stubs.
 *
 * <p>The full put → commit → query round-trip and the metrics /
 * tombstones / dimension-validation assertions live in
 * {@code VectorsRoundTripTest}.
 */
@QuarkusTest
class VectorsResourceTest extends AbstractResourceTest {

  private static final String INDEX_PATH = "/v1/indexes/demo/products";

  @Test
  void deferredEndpointsStillEnforceAuthentication() {
    given().when().get(INDEX_PATH + "/stats").then().statusCode(401);
  }

  @Test
  void deferredEndpointsStillEnforceBucketScope() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, OTHER_TOKEN)
        .when()
        .get(INDEX_PATH + "/stats")
        .then()
        .statusCode(403);
  }
}
