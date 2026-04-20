package io.github.zznate.vectorstore.storage.cache;

/**
 * Composite key identifying a fixed-size byte block within an object. The
 * string object key is typically {@code <bucket>/<key>} so one cache can
 * serve multiple buckets without collisions.
 */
public record BlockKey(String objectKey, long blockIndex) {}
