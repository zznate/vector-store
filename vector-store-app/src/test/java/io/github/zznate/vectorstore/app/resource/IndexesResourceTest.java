package io.github.zznate.vectorstore.app.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class IndexesResourceTest extends AbstractResourceTest {

  @Inject BucketRepository buckets;

  @BeforeEach
  void seedBuckets() {
    buckets.create(Bucket.active(DEMO_BUCKET, "Demo", clock.instant()));
    buckets.create(Bucket.active(OTHER_BUCKET, "Other", clock.instant()));
  }

  @Test
  void adminCreatesAndListsIndex() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"indexId":"products","displayName":"Products","dimension":1024,"metric":"COSINE","engineParams":{"m":32,"beamWidth":200}}
            """)
        .when()
        .post("/v1/buckets/demo/indexes")
        .then()
        .statusCode(201)
        .body("indexId", is("products"))
        .body("bucketId", is("demo"))
        .body("dimension", is(1024))
        .body("metric", is("COSINE"))
        .body("engineParams.m", is(32))
        .body("engineParams.beamWidth", is(200));

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets/demo/indexes")
        .then()
        .statusCode(200)
        .body("indexId", contains("products"));
  }

  @Test
  void bucketScopedKeyCreatesIndexInMatchingBucket() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, DEMO_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"indexId":"products","displayName":"Products","dimension":4,"metric":"COSINE","engineParams":{}}
            """)
        .when()
        .post("/v1/buckets/demo/indexes")
        .then()
        .statusCode(201);
  }

  @Test
  void bucketScopedKeyAgainstOtherBucketIsForbidden() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, DEMO_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"indexId":"x","displayName":"X","dimension":4,"metric":"COSINE","engineParams":{}}
            """)
        .when()
        .post("/v1/buckets/other/indexes")
        .then()
        .statusCode(403)
        .body("error", is("forbidden"));
  }

  @Test
  void missingApiKeyAgainstIndexesIsUnauthorized() {
    given()
        .when()
        .get("/v1/buckets/demo/indexes")
        .then()
        .statusCode(401);
  }

  @Test
  void getMissingIndexIsNotFound() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets/demo/indexes/missing")
        .then()
        .statusCode(404)
        .body("error", is("index_not_found"));
  }

  @Test
  void listIndexesInEmptyBucketIsEmpty() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets/demo/indexes")
        .then()
        .statusCode(200)
        .body("$", empty());
  }

  @Test
  void adminDeletesIndex() {
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
        .delete("/v1/buckets/demo/indexes/products")
        .then()
        .statusCode(204);

    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get("/v1/buckets/demo/indexes/products")
        .then()
        .statusCode(404);
  }

  @Test
  void creatingIndexInMissingBucketIsNotFound() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"indexId":"x","displayName":"X","dimension":4,"metric":"COSINE","engineParams":{}}
            """)
        .when()
        .post("/v1/buckets/nosuch/indexes")
        .then()
        .statusCode(404)
        .body("error", is("bucket_not_found"));
  }
}
