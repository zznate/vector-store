package io.github.zznate.vectorstore.app.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.api.auth.ApiKeyAuthenticationFilter;
import io.github.zznate.vectorstore.api.auth.PasswordHasher;
import io.github.zznate.vectorstore.app.testresource.MinioTestResource;
import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import io.github.zznate.vectorstore.testsupport.fixtures.FixtureChunk;
import io.github.zznate.vectorstore.testsupport.fixtures.FixtureQuery;
import io.github.zznate.vectorstore.testsupport.fixtures.RecallFixture;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end recall measurement for the extended filter grammar against
 * real MiniLM-L6-v2 embeddings. Ingests the 184-chunk Wikipedia fixture
 * via REST, commits one segment, and runs the 20 fixture queries through
 * each filter shape. Recall@10 is measured against a brute-force ground
 * truth restricted to the same accept set; the assertion floor is the
 * prompt-07 spec target of 0.85.
 *
 * <p>Sets up the catalog + bucket + index + corpus once in
 * {@link #ingestCorpusOnce()} ({@code @TestInstance(PER_CLASS)} so it
 * does not extend the standard {@code AbstractResourceTest} which
 * truncates between tests). Every {@code @Test} re-uses the committed
 * segment.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class WikipediaFilteredRecallIT {

  private static final String ADMIN_TOKEN = "admin-test.admin-secret";
  private static final String BUCKET = "wiki-recall";
  private static final String INDEX = "wiki-recall-index";
  private static final int TOP_K = 10;
  private static final double RECALL_FLOOR = 0.85;

  // Static so the corpus + queries + ingestion survive across test methods
  // without re-running setup each time. @TestInstance(PER_CLASS) + @BeforeAll
  // does not work cleanly with Quarkus's RestAssured port configuration; a
  // static guard on @BeforeEach gives the same one-shot behaviour without
  // racing the Quarkus test lifecycle.
  private static List<FixtureChunk> CORPUS;
  private static List<FixtureQuery> QUERIES;
  private static boolean PREPARED;

  @Inject Jdbi jdbi;
  @Inject ApiKeyRepository apiKeys;
  @Inject PasswordHasher hasher;
  @Inject Clock clock;

  @BeforeEach
  void ingestCorpusOnce() throws Exception {
    if (PREPARED) {
      return;
    }
    RestAssured.urlEncodingEnabled = false;
    resetCatalogAndSeedAdmin();
    CORPUS = RecallFixture.loadCorpus();
    QUERIES = RecallFixture.loadQueries();
    int dim = CORPUS.get(0).embedding().length;

    createBucket();
    createIndex(dim);
    putAllVectors();
    commitIndex();
    PREPARED = true;
  }

  @Test
  void equalityFilterMeetsRecallFloor() {
    double recall =
        aggregateRecall(
            query -> "{\"articleSlug\":\"" + query.expectedArticleSlug() + "\"}",
            (query, chunk) -> chunk.articleSlug().equals(query.expectedArticleSlug()));
    assertThat(recall).as("equality recall@10").isGreaterThanOrEqualTo(RECALL_FLOOR);
  }

  @Test
  void inFilterMeetsRecallFloor() {
    double recall =
        aggregateRecall(
            query -> jsonInFilter(slugSet(query)),
            (query, chunk) -> slugSet(query).contains(chunk.articleSlug()));
    assertThat(recall).as("$in recall@10").isGreaterThanOrEqualTo(RECALL_FLOOR);
  }

  @Test
  void notFilterMeetsRecallFloor() {
    String excluded = "transformer-deep-learning";
    double recall =
        aggregateRecall(
            query -> "{\"$not\":{\"articleSlug\":\"" + excluded + "\"}}",
            (query, chunk) -> !chunk.articleSlug().equals(excluded));
    assertThat(recall).as("$not recall@10").isGreaterThanOrEqualTo(RECALL_FLOOR);
  }

  @Test
  void orFilterMeetsRecallFloor() {
    double recall =
        aggregateRecall(
            query -> jsonOrFilter(slugSet(query)),
            (query, chunk) -> slugSet(query).contains(chunk.articleSlug()));
    assertThat(recall).as("$or recall@10").isGreaterThanOrEqualTo(RECALL_FLOOR);
  }

  // ---- aggregation ----

  private double aggregateRecall(
      Function<FixtureQuery, String> filterFactory,
      BiPredicate<FixtureQuery, FixtureChunk> bruteForcePredicate) {
    int totalCorrect = 0;
    int totalGroundTruth = 0;
    for (FixtureQuery query : QUERIES) {
      List<FixtureChunk> bruteTopK = bruteForceTopK(query, bruteForcePredicate);
      if (bruteTopK.isEmpty()) {
        continue;
      }
      List<String> returnedIds = queryService(query, filterFactory.apply(query));
      Set<String> returned = new HashSet<>(returnedIds);
      int matched = 0;
      for (FixtureChunk gt : bruteTopK) {
        if (returned.contains(gt.id())) {
          matched++;
        }
      }
      totalCorrect += matched;
      totalGroundTruth += bruteTopK.size();
    }
    return totalGroundTruth == 0 ? 0.0 : (double) totalCorrect / (double) totalGroundTruth;
  }

  private List<FixtureChunk> bruteForceTopK(
      FixtureQuery query, BiPredicate<FixtureQuery, FixtureChunk> predicate) {
    record Scored(FixtureChunk chunk, double score) {}
    List<Scored> scored = new ArrayList<>();
    for (FixtureChunk c : CORPUS) {
      if (!predicate.test(query, c)) {
        continue;
      }
      scored.add(new Scored(c, cosine(query.embedding(), c.embedding())));
    }
    scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
    int n = Math.min(TOP_K, scored.size());
    List<FixtureChunk> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(scored.get(i).chunk);
    }
    return out;
  }

  private List<String> queryService(FixtureQuery query, String filterJson) {
    StringBuilder body = new StringBuilder("{\"vector\":[");
    float[] e = query.embedding();
    for (int i = 0; i < e.length; i++) {
      if (i > 0) {
        body.append(",");
      }
      body.append(e[i]);
    }
    body.append("],\"topK\":").append(TOP_K).append(",\"filter\":").append(filterJson).append("}");
    Response resp =
        given()
            .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
            .contentType(ContentType.JSON)
            .body(body.toString())
            .when()
            .post("/v1/indexes/" + BUCKET + "/" + INDEX + "/vectors:query");
    resp.then().statusCode(200);
    return resp.jsonPath().getList("hits.id");
  }

  // ---- filter shapes ----

  /** Expected slug + a stable distractor; used by both $in and $or paths. */
  private static Set<String> slugSet(FixtureQuery query) {
    String distractor =
        "vector-database".equals(query.expectedArticleSlug()) ? "word2vec" : "vector-database";
    return Set.of(query.expectedArticleSlug(), distractor);
  }

  private static String jsonInFilter(Set<String> slugs) {
    StringBuilder b = new StringBuilder("{\"articleSlug\":{\"$in\":[");
    boolean first = true;
    for (String s : slugs) {
      if (!first) {
        b.append(",");
      }
      b.append("\"").append(s).append("\"");
      first = false;
    }
    b.append("]}}");
    return b.toString();
  }

  private static String jsonOrFilter(Set<String> slugs) {
    StringBuilder b = new StringBuilder("{\"$or\":[");
    boolean first = true;
    for (String s : slugs) {
      if (!first) {
        b.append(",");
      }
      b.append("{\"articleSlug\":\"").append(s).append("\"}");
      first = false;
    }
    b.append("]}");
    return b.toString();
  }

  // ---- ingest helpers ----

  private void resetCatalogAndSeedAdmin() {
    jdbi.useHandle(
        h -> {
          h.execute("DELETE FROM manifest_version");
          h.execute("DELETE FROM segment");
          h.execute("DELETE FROM vector_index");
          h.execute("DELETE FROM vector_bucket");
          h.execute("DELETE FROM api_key");
        });
    apiKeys.create(
        new ApiKey("admin-test", hasher.hash("admin-secret"), null, clock.instant(), null));
  }

  private void createBucket() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body("{\"bucketId\":\"" + BUCKET + "\",\"displayName\":\"Wikipedia recall\"}")
        .when()
        .post("/v1/buckets")
        .then()
        .statusCode(201);
  }

  private void createIndex(int dimension) {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(
            "{\"indexId\":\""
                + INDEX
                + "\",\"displayName\":\"Wikipedia recall\",\"dimension\":"
                + dimension
                + ",\"metric\":\"COSINE\",\"engineParams\":{\"addHierarchy\":true,\"beamWidth\":300}}")
        .when()
        .post("/v1/buckets/" + BUCKET + "/indexes")
        .then()
        .statusCode(201);
  }

  private void putAllVectors() {
    StringBuilder body = new StringBuilder("{\"vectors\":[");
    for (int i = 0; i < CORPUS.size(); i++) {
      FixtureChunk c = CORPUS.get(i);
      if (i > 0) {
        body.append(",");
      }
      body.append("{\"id\":\"").append(c.id()).append("\",\"vector\":[");
      float[] v = c.embedding();
      for (int j = 0; j < v.length; j++) {
        if (j > 0) {
          body.append(",");
        }
        body.append(v[j]);
      }
      body.append("],\"attributes\":{\"articleSlug\":\"").append(c.articleSlug()).append("\"}}");
    }
    body.append("]}");
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .contentType(ContentType.JSON)
        .body(body.toString())
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + "/vectors:put")
        .then()
        .statusCode(202);
  }

  private void commitIndex() {
    given()
        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_TOKEN)
        .when()
        .post("/v1/indexes/" + BUCKET + "/" + INDEX + ":commit")
        .then()
        .statusCode(200);
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0.0;
    double na = 0.0;
    double nb = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
