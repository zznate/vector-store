package io.github.zznate.vectorstore.engine.testsupport;

/**
 * One line of {@code src/test/resources/recall/corpus.jsonl}. Mirrors the
 * shape {@code vector-store-datagen} writes; kept here as a local copy so
 * the engine tests don't take a test-time dependency on the datagen module.
 */
public record FixtureChunk(
    String id, String articleSlug, int ordinalInArticle, String text, float[] embedding) {}
