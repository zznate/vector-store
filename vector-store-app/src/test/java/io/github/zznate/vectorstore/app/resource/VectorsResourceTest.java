package io.github.zznate.vectorstore.app.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * All six vector-level endpoints are 501 stubs in prompt 01; this suite
 * exercises that every one of them returns the structured {error, message}
 * envelope with the prompt number formatted in.
 */
@QuarkusTest
class VectorsResourceTest extends AbstractResourceTest {

  private static final String INDEX_PATH = "/v1/indexes/demo/products";

  @Test
  void putVectorsIsNotImplemented() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            """
            {"vectors":[{"id":"a","vector":[1.0,2.0,3.0,4.0],"attributes":{"k":"v"}}]}
            """)
        .when()
        .post(INDEX_PATH + "/vectors:put")
        .then()
        .statusCode(501)
        .body("error", is("not_implemented"))
        .body("message", containsString("vectors:put"))
        .body("message", containsString("prompt 02"));
  }

  @Test
  void queryVectorsIsNotImplemented() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("""
            {"vector":[1.0,2.0,3.0,4.0],"topK":10}
            """)
        .when()
        .post(INDEX_PATH + "/vectors:query")
        .then()
        .statusCode(501)
        .body("message", containsString("vectors:query"))
        .body("message", containsString("prompt 02"));
  }

  @Test
  void deleteVectorsIsNotImplemented() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("""
            {"ids":["a","b"]}
            """)
        .when()
        .post(INDEX_PATH + "/vectors:delete")
        .then()
        .statusCode(501)
        .body("message", containsString("vectors:delete"))
        .body("message", containsString("prompt 04"));
  }

  @Test
  void getVectorIsNotImplemented() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get(INDEX_PATH + "/vectors/a")
        .then()
        .statusCode(501)
        .body("message", containsString("vectors:get"))
        .body("message", containsString("prompt 02"));
  }

  @Test
  void commitIsNotImplemented() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post(INDEX_PATH + ":commit")
        .then()
        .statusCode(501)
        .body("message", containsString("commit"))
        .body("message", containsString("prompt 02"));
  }

  @Test
  void statsIsNotImplemented() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .get(INDEX_PATH + "/stats")
        .then()
        .statusCode(501)
        .body("message", containsString("stats"))
        .body("message", containsString("prompt 02"));
  }

  @Test
  void deferredEndpointsStillEnforceAuthentication() {
    given()
        .when()
        .get(INDEX_PATH + "/stats")
        .then()
        .statusCode(401);
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
