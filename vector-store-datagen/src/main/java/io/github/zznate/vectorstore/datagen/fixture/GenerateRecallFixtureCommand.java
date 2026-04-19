package io.github.zznate.vectorstore.datagen.fixture;

import io.github.zznate.vectorstore.datagen.chunk.Chunk;
import io.github.zznate.vectorstore.datagen.chunk.TextChunker;
import io.github.zznate.vectorstore.datagen.embed.Embedder;
import io.github.zznate.vectorstore.datagen.embed.MiniLmEmbedder;
import io.github.zznate.vectorstore.datagen.wikipedia.WikipediaArticle;
import io.github.zznate.vectorstore.datagen.wikipedia.WikipediaFetcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the recall-fixture pipeline: fetch -&gt; chunk -&gt; embed -&gt;
 * write.
 */
public final class GenerateRecallFixtureCommand {

  /**
   * Canonical ML corpus. Slug (kebab-case, stable in tests and filenames)
   * mapped to the Wikipedia title (spaces as underscores, URL-safe).
   */
  private static final Map<String, String> ARTICLES = new LinkedHashMap<>();

  static {
    ARTICLES.put("nearest-neighbor-search", "Nearest_neighbor_search");
    ARTICLES.put("hierarchical-navigable-small-world", "Hierarchical_navigable_small_world");
    ARTICLES.put("vector-database", "Vector_database");
    ARTICLES.put("vector-quantization", "Vector_quantization");
    ARTICLES.put("cosine-similarity", "Cosine_similarity");
    ARTICLES.put("locality-sensitive-hashing", "Locality-sensitive_hashing");
    ARTICLES.put("word2vec", "Word2vec");
    ARTICLES.put("transformer-deep-learning", "Transformer_(deep_learning)");
    ARTICLES.put("retrieval-augmented-generation", "Retrieval-augmented_generation");
    ARTICLES.put("k-nearest-neighbors", "K-nearest_neighbors_algorithm");
  }

  /** Pre-defined query set. Each query is labelled with the article it should resolve to. */
  private static final List<QuerySpec> QUERIES =
      List.of(
          new QuerySpec("q-01", "How are HNSW graph layers built?", "hierarchical-navigable-small-world"),
          new QuerySpec("q-02", "What is cosine similarity used for?", "cosine-similarity"),
          new QuerySpec("q-03", "How does product quantization compress vectors?", "vector-quantization"),
          new QuerySpec("q-04", "What is a transformer model in deep learning?", "transformer-deep-learning"),
          new QuerySpec("q-05", "How does retrieval augmented generation work?", "retrieval-augmented-generation"),
          new QuerySpec("q-06", "What is locality sensitive hashing?", "locality-sensitive-hashing"),
          new QuerySpec("q-07", "What does Word2vec learn?", "word2vec"),
          new QuerySpec("q-08", "What is a vector database?", "vector-database"),
          new QuerySpec("q-09", "How does the k-nearest neighbors algorithm classify points?", "k-nearest-neighbors"),
          new QuerySpec("q-10", "What is nearest neighbor search?", "nearest-neighbor-search"),
          new QuerySpec("q-11", "Why does HNSW use multiple layers?", "hierarchical-navigable-small-world"),
          new QuerySpec("q-12", "How are word embeddings learned from a corpus?", "word2vec"),
          new QuerySpec("q-13", "What is the self-attention mechanism in transformers?", "transformer-deep-learning"),
          new QuerySpec("q-14", "How does PQ quantize vectors with codebooks?", "vector-quantization"),
          new QuerySpec("q-15", "Why is cosine similarity preferred for text embeddings?", "cosine-similarity"),
          new QuerySpec("q-16", "How do hash functions preserve similarity in LSH?", "locality-sensitive-hashing"),
          new QuerySpec("q-17", "When should I use RAG instead of fine-tuning?", "retrieval-augmented-generation"),
          new QuerySpec("q-18", "What problems does KNN classification solve?", "k-nearest-neighbors"),
          new QuerySpec("q-19", "How do approximate nearest neighbor methods trade recall for speed?", "nearest-neighbor-search"),
          new QuerySpec("q-20", "What features differentiate vector databases from traditional ones?", "vector-database"));

  private static final int TARGET_WORDS = 180;
  private static final int OVERLAP_WORDS = 30;

  public static void run(String[] args) throws Exception {
    String outputPath = requireArg(args, "--output");
    Path outputDir = Path.of(outputPath);

    System.out.println("vector-store-datagen — generate-recall-fixture");
    System.out.println("  output: " + outputDir.toAbsolutePath());
    System.out.println("  articles: " + ARTICLES.size());
    System.out.println("  queries: " + QUERIES.size());
    System.out.println();

    System.out.println("Fetching articles …");
    WikipediaFetcher fetcher = new WikipediaFetcher();
    List<WikipediaArticle> articles = new ArrayList<>(ARTICLES.size());
    for (var entry : ARTICLES.entrySet()) {
      WikipediaArticle article = fetcher.fetch(entry.getKey(), entry.getValue());
      System.out.printf(
          "  %-36s  oldid=%d  %d chars%n",
          article.slug(), article.oldid(), article.plainText().length());
      articles.add(article);
    }
    System.out.println();

    System.out.println("Chunking …");
    TextChunker chunker = new TextChunker(TARGET_WORDS, OVERLAP_WORDS);
    List<Chunk> allChunks = new ArrayList<>();
    for (WikipediaArticle article : articles) {
      List<Chunk> articleChunks = chunker.chunk(article);
      System.out.printf("  %-36s  %d chunks%n", article.slug(), articleChunks.size());
      allChunks.addAll(articleChunks);
    }
    System.out.println("  total chunks: " + allChunks.size());
    System.out.println();

    System.out.println("Loading embedder (this downloads ~400 MB on first run) …");
    try (Embedder embedder = new MiniLmEmbedder()) {
      System.out.println("  model: " + embedder.modelId() + "  dim=" + embedder.dimension());
      System.out.println();

      System.out.println("Embedding corpus …");
      List<CorpusChunk> corpus = new ArrayList<>(allChunks.size());
      for (Chunk c : allChunks) {
        corpus.add(
            new CorpusChunk(c.id(), c.articleSlug(), c.ordinalInArticle(), c.text(), embedder.embed(c.text())));
      }
      System.out.println("  embedded " + corpus.size() + " chunks");
      System.out.println();

      System.out.println("Embedding queries …");
      List<CorpusQuery> queries = new ArrayList<>(QUERIES.size());
      for (QuerySpec q : QUERIES) {
        queries.add(new CorpusQuery(q.id(), q.text(), q.expectedArticleSlug(), embedder.embed(q.text())));
      }
      System.out.println("  embedded " + queries.size() + " queries");
      System.out.println();

      System.out.println("Writing fixture …");
      new RecallFixtureWriter(outputDir)
          .write(corpus, queries, articles, embedder.modelId(), embedder.dimension(), TARGET_WORDS, OVERLAP_WORDS);
      System.out.println("  wrote corpus.jsonl, queries.jsonl, README.md");
    }

    System.out.println();
    System.out.println("Done.");
  }

  private static String requireArg(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    throw new IllegalArgumentException("missing required argument: " + name);
  }

  private record QuerySpec(String id, String text, String expectedArticleSlug) {}
}
