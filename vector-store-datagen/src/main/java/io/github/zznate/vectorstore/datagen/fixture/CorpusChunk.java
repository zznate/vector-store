package io.github.zznate.vectorstore.datagen.fixture;

/**
 * One line of {@code corpus.jsonl}. Consumed directly by the engine's
 * recall test.
 */
public record CorpusChunk(
    String id, String articleSlug, int ordinalInArticle, String text, float[] embedding) {}
