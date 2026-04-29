package io.github.zznate.vectorstore.testsupport.fixtures;

/** One line of {@code recall/queries.jsonl}. */
public record FixtureQuery(
    String id, String text, String expectedArticleSlug, float[] embedding) {}
