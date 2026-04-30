package io.github.zznate.vectorstore.app.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BucketsResourceTest extends AbstractResourceTest {

  @Inject BucketRepository buckets;

  @Test
  void adminCreatesThenListsThenGetsThenDeletesBucket() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"bucketId":"demo","displayName":"Demo bucket"}
            """)
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(201)
        .body("bucketId", is("demo"))
        .body("displayName", is("Demo bucket"));

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets")
        .then()
        .statusCode(200)
        .body("bucketId", contains("demo"));

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets/demo")
        .then()
        .statusCode(200)
        .body("bucketId", is("demo"));

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .delete("/v1/buckets/demo")
        .then()
        .statusCode(204);
  }

  @Test
  void missingApiKeyIsUnauthorized() {
    given()
        .contentType(ContentType.JSON)
        .body("""
            {"bucketId":"demo","displayName":"Demo"}
            """)
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(401)
        .body("error", is("unauthorized"));
  }

  @Test
  void invalidApiKeyIsUnauthorized() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, "bogus.key")
        .when()
        .get("/v1/buckets")
        .then()
        .statusCode(401)
        .body("error", is("unauthorized"));
  }

  @Test
  void bucketScopedKeyAgainstAdminEndpointIsForbidden() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, DEMO_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"bucketId":"x","displayName":"X"}
            """)
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(403)
        .body("error", is("forbidden"));
  }

  @Test
  void duplicateBucketIsConflict() {
    buckets.create(Bucket.active(DEMO_BUCKET, "Existing", clock.instant()));

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"bucketId":"demo","displayName":"Another"}
            """)
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(409)
        .body("error", is("bucket_already_exists"));
  }

  @Test
  void missingBucketIsNotFound() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets/missing")
        .then()
        .statusCode(404)
        .body("error", is("bucket_not_found"));
  }

  @Test
  void deletingNonEmptyBucketIsConflict() {
    buckets.create(Bucket.active(DEMO_BUCKET, "Demo", clock.instant()));

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"indexId":"products","displayName":"Products","dimension":4,"metric":"COSINE","engineParams":{}}
            """)
        .when()
        .post("/v1/buckets/demo/indexes")
        .then()
        .statusCode(201);

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .delete("/v1/buckets/demo")
        .then()
        .statusCode(409)
        .body("error", is("bucket_not_empty"));
  }
}
