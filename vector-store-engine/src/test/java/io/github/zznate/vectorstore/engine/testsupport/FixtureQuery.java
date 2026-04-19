package io.github.zznate.vectorstore.engine.testsupport;

/** One line of {@code src/test/resources/recall/queries.jsonl}. */
public record FixtureQuery(
    String id, String text, String expectedArticleSlug, float[] embedding) {}
