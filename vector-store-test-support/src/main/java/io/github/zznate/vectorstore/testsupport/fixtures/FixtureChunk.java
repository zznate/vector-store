package io.github.zznate.vectorstore.testsupport.fixtures;

/**
 * One line of {@code recall/corpus.jsonl}. Mirrors the shape that
 * {@code vector-store-datagen} writes; consumed by {@link RecallFixture}.
 */
public record FixtureChunk(
    String id, String articleSlug, int ordinalInArticle, String text, float[] embedding) {}
