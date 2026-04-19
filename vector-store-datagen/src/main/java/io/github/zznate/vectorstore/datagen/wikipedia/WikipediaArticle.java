package io.github.zznate.vectorstore.datagen.wikipedia;

/**
 * One Wikipedia article as fetched from the live API. {@code oldid} is the
 * specific revision id returned by the server — committed to the fixture
 * README so regeneration can pin to the exact same revision later.
 */
public record WikipediaArticle(String slug, String title, long oldid, String plainText) {}
