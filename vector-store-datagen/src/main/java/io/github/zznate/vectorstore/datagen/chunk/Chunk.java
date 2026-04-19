package io.github.zznate.vectorstore.datagen.chunk;

/**
 * A contiguous span of text from a single Wikipedia article, pre-embedded
 * and written to the corpus fixture.
 *
 * <p>{@code articleSlug} is the kebab-case identifier used throughout the
 * fixture and tests; {@code id} is globally unique and matches the user-id
 * we feed into {@code vectors:put} at test time.
 */
public record Chunk(
    String id, String articleSlug, int ordinalInArticle, String text) {}
