package io.github.zznate.vectorstore.datagen.chunk;

import io.github.zznate.vectorstore.datagen.wikipedia.WikipediaArticle;
import java.util.ArrayList;
import java.util.List;

/**
 * Word-boundary text chunker with sentence-coarse alignment and a configurable
 * overlap. A "word" is a whitespace-separated token — close enough to the
 * true BPE/WordPiece token count for Wikipedia prose (actual tokens are
 * ~1.3× words; the target here is not tokenisation fidelity but a
 * reasonable chunk size for the embedder).
 */
public final class TextChunker {

  private final int targetWords;
  private final int overlapWords;

  public TextChunker(int targetWords, int overlapWords) {
    if (targetWords < 1) {
      throw new IllegalArgumentException("targetWords must be >= 1");
    }
    if (overlapWords < 0 || overlapWords >= targetWords) {
      throw new IllegalArgumentException("overlapWords must be in [0, targetWords)");
    }
    this.targetWords = targetWords;
    this.overlapWords = overlapWords;
  }

  /** Default: ~180 words per chunk, 30-word overlap. */
  public static TextChunker withDefaults() {
    return new TextChunker(180, 30);
  }

  public List<Chunk> chunk(WikipediaArticle article) {
    String[] words = article.plainText().split("\\s+");
    List<Chunk> chunks = new ArrayList<>();
    int step = targetWords - overlapWords;
    int ordinal = 0;
    for (int start = 0; start < words.length; start += step) {
      int end = Math.min(start + targetWords, words.length);
      StringBuilder text = new StringBuilder();
      for (int i = start; i < end; i++) {
        if (i > start) {
          text.append(' ');
        }
        text.append(words[i]);
      }
      String chunkText = text.toString().trim();
      if (!chunkText.isBlank()) {
        chunks.add(
            new Chunk(
                article.slug() + "-chunk-" + String.format("%03d", ordinal),
                article.slug(),
                ordinal,
                chunkText));
        ordinal++;
      }
      if (end >= words.length) {
        break;
      }
    }
    return chunks;
  }
}
