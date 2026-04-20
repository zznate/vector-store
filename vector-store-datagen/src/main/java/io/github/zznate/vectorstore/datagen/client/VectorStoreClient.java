package io.github.zznate.vectorstore.datagen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin JDK {@link HttpClient} wrapper for the vector-store REST surface.
 * Purpose-built for the datagen module's driver commands: no retries, no
 * streaming, no pooling beyond what JDK provides — every call is one HTTP
 * round-trip. Intended for local end-to-end exercise, not production
 * client duty.
 */
public final class VectorStoreClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final URI baseUri;
  private final String apiKey;

  public VectorStoreClient(String baseUrl, String apiKey) {
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.baseUri = URI.create(baseUrl.replaceAll("/+$", ""));
    this.apiKey = apiKey;
  }

  /**
   * POST a create-bucket request. Returns {@code true} if the bucket was
   * created (201), {@code false} if it already existed (409). Any other
   * status throws.
   */
  public boolean createBucket(String bucketId, String displayName) throws IOException {
    ObjectNode body = JSON.createObjectNode();
    body.put("bucketId", bucketId);
    body.put("displayName", displayName);
    Response r = post("/v1/buckets", body);
    if (r.status == 201) {
      return true;
    }
    if (r.status == 409) {
      return false;
    }
    throw unexpected("createBucket", r);
  }

  /**
   * POST a create-index request with the given dimension and cosine
   * metric. Returns {@code true} on 201, {@code false} on 409.
   */
  public boolean createIndex(
      String bucketId,
      String indexId,
      String displayName,
      int dimension,
      String metric,
      Map<String, Object> engineParams)
      throws IOException {
    ObjectNode body = JSON.createObjectNode();
    body.put("indexId", indexId);
    body.put("displayName", displayName);
    body.put("dimension", dimension);
    body.put("metric", metric);
    body.set(
        "engineParams",
        engineParams == null ? JSON.createObjectNode() : JSON.valueToTree(engineParams));
    Response r = post("/v1/buckets/" + bucketId + "/indexes", body);
    if (r.status == 201) {
      return true;
    }
    if (r.status == 409) {
      return false;
    }
    throw unexpected("createIndex", r);
  }

  /**
   * POST a batch of vectors to {@code /vectors:put}. Each entry in
   * {@code batch} must carry an id, a float[] vector, and a
   * (possibly-empty) attribute map. Expects 202.
   */
  public void putVectors(String bucketId, String indexId, List<VectorUpsert> batch)
      throws IOException {
    ObjectNode body = JSON.createObjectNode();
    ArrayNode vectors = body.putArray("vectors");
    for (VectorUpsert v : batch) {
      ObjectNode entry = vectors.addObject();
      entry.put("id", v.id());
      ArrayNode vec = entry.putArray("vector");
      for (float f : v.vector()) {
        vec.add(f);
      }
      ObjectNode attrs = entry.putObject("attributes");
      for (Map.Entry<String, String> e : v.attributes().entrySet()) {
        attrs.put(e.getKey(), e.getValue());
      }
    }
    Response r = post("/v1/indexes/" + bucketId + "/" + indexId + "/vectors:put", body);
    if (r.status != 202) {
      throw unexpected("putVectors", r);
    }
  }

  /** POST {@code :commit}. Returns the parsed response body on 200. */
  public JsonNode commit(String bucketId, String indexId) throws IOException {
    Response r = post("/v1/indexes/" + bucketId + "/" + indexId + ":commit", null);
    if (r.status != 200) {
      throw unexpected("commit", r);
    }
    return JSON.readTree(r.body);
  }

  private Response post(String path, JsonNode body) throws IOException {
    byte[] payload = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    try {
      HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      return new Response(resp.statusCode(), resp.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted during " + path, e);
    }
  }

  private static IOException unexpected(String op, Response r) {
    return new IOException(
        op + " unexpected status " + r.status + ": " + new String(r.body, StandardCharsets.UTF_8));
  }

  private record Response(int status, byte[] body) {}

  /** Simple holder the ingest command populates per corpus chunk. */
  public record VectorUpsert(String id, float[] vector, Map<String, String> attributes) {}
}
