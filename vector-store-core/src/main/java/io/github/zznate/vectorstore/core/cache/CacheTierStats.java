package io.github.zznate.vectorstore.core.cache;

/**
 * Aggregate counters exposed by a {@link CacheTier} or {@link L2Provider}.
 * {@code currentBytes} is {@code -1} when the implementation is bounded by
 * entry count rather than bytes; {@code maxBytes} is {@code -1} when the
 * cache is unbounded (none should be in production, but tests use it).
 */
public record CacheTierStats(
    long hitCount,
    long missCount,
    long evictionCount,
    long currentBytes,
    long maxBytes,
    long currentEntries) {}
