package io.github.zznate.vectorstore.datagen.fixture;

/**
 * One line of {@code queries.jsonl}. {@code expectedArticleSlug} is the
 * slug of the Wikipedia article whose chunks should dominate the top-k
 * results for this query.
 */
public record CorpusQuery(
    String id, String text, String expectedArticleSlug, float[] embedding) {}
