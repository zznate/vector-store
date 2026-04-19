package io.github.zznate.vectorstore.datagen.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the three-file fixture, checks the obvious invariants, prints a
 * summary. Exits non-zero on any failure so CI can catch fixture drift if
 * we ever decide to wire it in.
 */
public final class ValidateFixtureCommand {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static void run(String[] args) throws Exception {
    String inputPath = requireArg(args, "--input");
    Path input = Path.of(inputPath);

    Path corpusPath = input.resolve("corpus.jsonl");
    Path queriesPath = input.resolve("queries.jsonl");
    Path readmePath = input.resolve("README.md");

    check(Files.exists(corpusPath), "missing corpus.jsonl");
    check(Files.exists(queriesPath), "missing queries.jsonl");
    check(Files.exists(readmePath), "missing README.md");

    List<CorpusChunk> chunks = readJsonl(corpusPath, CorpusChunk.class);
    List<CorpusQuery> queries = readJsonl(queriesPath, CorpusQuery.class);

    check(!chunks.isEmpty(), "corpus.jsonl is empty");
    check(!queries.isEmpty(), "queries.jsonl is empty");

    int dim = chunks.get(0).embedding().length;
    Set<String> slugs = new HashSet<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < chunks.size(); i++) {
      CorpusChunk c = chunks.get(i);
      check(c.id() != null && !c.id().isBlank(), "chunk " + i + " has no id");
      check(c.articleSlug() != null && !c.articleSlug().isBlank(), "chunk " + c.id() + " has no articleSlug");
      check(c.text() != null && !c.text().isBlank(), "chunk " + c.id() + " has no text");
      check(c.embedding() != null && c.embedding().length == dim,
          "chunk " + c.id() + " embedding dim mismatch (got " + (c.embedding() == null ? "null" : c.embedding().length) + ", expected " + dim + ")");
      checkFinite(c.embedding(), "chunk " + c.id());
      check(ids.add(c.id()), "duplicate chunk id: " + c.id());
      slugs.add(c.articleSlug());
    }

    Set<String> queryIds = new HashSet<>();
    for (int i = 0; i < queries.size(); i++) {
      CorpusQuery q = queries.get(i);
      check(q.id() != null && !q.id().isBlank(), "query " + i + " has no id");
      check(q.text() != null && !q.text().isBlank(), "query " + q.id() + " has no text");
      check(q.expectedArticleSlug() != null && !q.expectedArticleSlug().isBlank(),
          "query " + q.id() + " has no expectedArticleSlug");
      check(slugs.contains(q.expectedArticleSlug()),
          "query " + q.id() + " expects slug '" + q.expectedArticleSlug() + "' which is not in the corpus");
      check(q.embedding() != null && q.embedding().length == dim,
          "query " + q.id() + " embedding dim mismatch (got " + (q.embedding() == null ? "null" : q.embedding().length) + ", expected " + dim + ")");
      checkFinite(q.embedding(), "query " + q.id());
      check(queryIds.add(q.id()), "duplicate query id: " + q.id());
    }

    System.out.println("vector-store-datagen — validate-fixture");
    System.out.println("  input:    " + input.toAbsolutePath());
    System.out.println("  chunks:   " + chunks.size() + " across " + slugs.size() + " slugs");
    System.out.println("  queries:  " + queries.size());
    System.out.println("  dim:      " + dim);
    System.out.println("  OK");
  }

  private static <T> List<T> readJsonl(Path file, Class<T> type) throws IOException {
    List<T> out = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(file)) {
      String line;
      int lineNumber = 0;
      while ((line = r.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        try {
          out.add(JSON.readValue(line, type));
        } catch (IOException e) {
          throw new IOException(file + ":" + lineNumber + " — parse failed: " + e.getMessage(), e);
        }
      }
    }
    return out;
  }

  private static void check(boolean cond, String message) {
    if (!cond) {
      System.err.println("validation failed: " + message);
      throw new AssertionError(message);
    }
  }

  private static void checkFinite(float[] embedding, String context) {
    for (float v : embedding) {
      if (!Float.isFinite(v)) {
        check(false, context + " has non-finite value in embedding");
      }
    }
  }

  private static String requireArg(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    throw new IllegalArgumentException("missing required argument: " + name);
  }
}
