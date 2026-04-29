package io.github.zznate.vectorstore.testsupport.fixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the Wikipedia + MiniLM-L6-v2 recall fixture from this module's
 * resources. The fixture is generated offline by
 * {@code vector-store-datagen} and committed under
 * {@code src/main/resources/recall/}; CI never regenerates it.
 *
 * <p>Designed for use as a {@code <scope>test</scope>} dependency: any
 * sibling module's test code can call {@link #loadCorpus()} /
 * {@link #loadQueries()} without taking a runtime dependency on the
 * fixture.
 */
public final class RecallFixture {

  private static final ObjectMapper JSON = new ObjectMapper();

  private RecallFixture() {}

  public static List<FixtureChunk> loadCorpus() throws IOException {
    return loadJsonl("/recall/corpus.jsonl", FixtureChunk.class);
  }

  public static List<FixtureQuery> loadQueries() throws IOException {
    return loadJsonl("/recall/queries.jsonl", FixtureQuery.class);
  }

  private static <T> List<T> loadJsonl(String classpathResource, Class<T> type)
      throws IOException {
    try (InputStream in = RecallFixture.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IOException(
            "recall fixture not found on classpath at "
                + classpathResource
                + ". Run `./mvnw -pl vector-store-datagen exec:java@generate-recall-fixture` "
                + "and copy the output into vector-store-test-support/src/main/resources/recall/.");
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        List<T> out = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          out.add(JSON.readValue(line, type));
        }
        return out;
      }
    }
  }
}
