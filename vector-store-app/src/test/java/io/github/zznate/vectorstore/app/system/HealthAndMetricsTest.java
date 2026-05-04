package io.github.zznate.vectorstore.app.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies that management endpoints ({@code /q/health}, {@code /q/metrics},
 * {@code /q/openapi}) are reachable without authentication and that the
 * meters catalogued in {@code MetricNames} appear in the Prometheus
 * scrape output even before any traffic flows.
 */
@QuarkusTest
class HealthAndMetricsTest {

  @Test
  void healthIsPublic() {
    given().when().get("/q/health").then().statusCode(200);
  }

  @Test
  void metricsIsPublic() {
    given().when().get("/q/metrics").then().statusCode(200);
  }

  @Test
  void openApiSpecIsPublic() {
    given().when().get("/q/openapi").then().statusCode(200);
  }

  @Test
  void metricsIncludeEagerlyRegisteredVectorStoreMeters() {
    given()
        .when()
        .get("/q/metrics")
        .then()
        .statusCode(200)
        .body(
            allOf(
                containsString("vectorstore_put_vectors"),
                containsString("vectorstore_commit_duration"),
                containsString("vectorstore_query_duration"),
                containsString("vectorstore_storage_get_duration"),
                containsString("vectorstore_cache_hit"),
                containsString("vectorstore_cache_miss"),
                containsString("vectorstore_cache_eviction"),
                containsString("vectorstore_filter_compile_duration"),
                containsString("vectorstore_filter_strategy")));
  }
}
