package io.github.zznate.vectorstore.datagen.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zznate.vectorstore.datagen.client.VectorStoreClient;
import io.github.zznate.vectorstore.datagen.client.VectorStoreClient.VectorUpsert;
import io.github.zznate.vectorstore.datagen.fixture.CorpusChunk;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Push a previously-generated recall fixture (corpus.jsonl) into a running
 * vector-store service. Creates the bucket and index if they are missing
 * (409 on either is treated as "already there, reuse"), batch-puts every
 * chunk, and commits. Attributes carry the {@code articleSlug} so the
 * ingested corpus can be exercised with an equality filter in a follow-up
 * test without re-embedding anything.
 *
 * <p>Shape deliberately matches the {@code ValidateFixtureCommand} style
 * in this module: arg parsing via {@code --name value} pairs, public
 * static {@code run(String[])}.
 */
public final class IngestFixtureCommand {

  private static final ObjectMapper JSON = new ObjectMapper();

  private IngestFixtureCommand() {}

  public static void run(String[] args) throws Exception {
    Path input = Path.of(requireArg(args, "--input"));
    String endpoint = optionalArg(args, "--endpoint", "http://localhost:8080");
    String bucket = optionalArg(args, "--bucket", "demo");
    String index = optionalArg(args, "--index", "wikipedia");
    int batchSize = Integer.parseInt(optionalArg(args, "--batch-size", "256"));
    String metric = optionalArg(args, "--metric", "COSINE");

    String apiKey = resolveApiKey(args);
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println(
          "ingest-fixture: no API key. Provide --api-key <token> or export"
              + " VECTORSTORE_BOOTSTRAP_ADMIN_KEY before invoking.");
      System.exit(2);
    }

    Path corpusPath = input.resolve("corpus.jsonl");
    if (!Files.exists(corpusPath)) {
      throw new IllegalArgumentException("corpus.jsonl not found under " + input);
    }

    List<CorpusChunk> chunks = readCorpus(corpusPath);
    if (chunks.isEmpty()) {
      throw new IllegalStateException("corpus.jsonl is empty: nothing to ingest");
    }
    int dim = chunks.get(0).embedding().length;

    VectorStoreClient client = new VectorStoreClient(endpoint, apiKey);

    System.out.println("vector-store-datagen — ingest-fixture");
    System.out.println("  endpoint:  " + endpoint);
    System.out.println("  bucket:    " + bucket);
    System.out.println("  index:     " + index + " (dim=" + dim + ", metric=" + metric + ")");
    System.out.println("  input:     " + input.toAbsolutePath());
    System.out.println("  chunks:    " + chunks.size() + " (batch=" + batchSize + ")");
    System.out.println();

    boolean bucketCreated = client.createBucket(bucket, bucket);
    System.out.println(bucketCreated ? "  bucket created" : "  bucket already existed, reusing");
    boolean indexCreated = client.createIndex(bucket, index, index, dim, metric, Map.of());
    System.out.println(indexCreated ? "  index created" : "  index already existed, reusing");

    long startNanos = System.nanoTime();
    int pushed = 0;
    List<VectorUpsert> batch = new ArrayList<>(batchSize);
    for (CorpusChunk chunk : chunks) {
      batch.add(
          new VectorUpsert(
              chunk.id(),
              chunk.embedding(),
              Map.of("articleSlug", chunk.articleSlug())));
      if (batch.size() >= batchSize) {
        client.putVectors(bucket, index, batch);
        pushed += batch.size();
        System.out.println("  put: " + pushed + " / " + chunks.size());
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      client.putVectors(bucket, index, batch);
      pushed += batch.size();
      System.out.println("  put: " + pushed + " / " + chunks.size());
    }

    Duration putElapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    long commitStartNanos = System.nanoTime();
    JsonNode commitResult = client.commit(bucket, index);
    Duration commitElapsed = Duration.ofNanos(System.nanoTime() - commitStartNanos);

    System.out.println();
    System.out.println("commit OK");
    System.out.println("  segmentId:       " + commitResult.path("segmentId").asText());
    System.out.println("  vectorCount:     " + commitResult.path("vectorCount").asLong());
    System.out.println("  bytes:           " + commitResult.path("bytes").asLong());
    System.out.println("  manifestVersion: " + commitResult.path("manifestVersion").asInt());
    System.out.println();
    System.out.println("elapsed");
    System.out.println("  put phase:    " + format(putElapsed));
    System.out.println("  commit phase: " + format(commitElapsed));
  }

  private static List<CorpusChunk> readCorpus(Path path) throws IOException {
    List<CorpusChunk> out = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(path)) {
      String line;
      int ln = 0;
      while ((line = r.readLine()) != null) {
        ln++;
        if (line.isBlank()) {
          continue;
        }
        try {
          out.add(JSON.readValue(line, CorpusChunk.class));
        } catch (IOException e) {
          throw new IOException(path + ":" + ln + " — parse failed: " + e.getMessage(), e);
        }
      }
    }
    return out;
  }

  private static String resolveApiKey(String[] args) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals("--api-key")) {
        return args[i + 1];
      }
    }
    String env = System.getenv("VECTORSTORE_BOOTSTRAP_ADMIN_KEY");
    return env == null ? null : env.trim();
  }

  private static String requireArg(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    throw new IllegalArgumentException("missing required argument: " + name);
  }

  private static String optionalArg(String[] args, String name, String defaultValue) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    return defaultValue;
  }

  private static String format(Duration d) {
    long ms = d.toMillis();
    if (ms < 1000) {
      return ms + " ms";
    }
    return String.format("%.2f s", ms / 1000.0);
  }
}
