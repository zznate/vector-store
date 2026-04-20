package io.github.zznate.vectorstore.storage.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Process-wide on-heap cache of fixed-size object blocks. Configured with a
 * byte-weighted eviction budget so the heap footprint is bounded regardless
 * of how many segments share the cache. A phase-2 disk or Redis tier will
 * slot in behind this same type.
 *
 * <p>The decorator reader owns hit/miss accounting; this class intentionally
 * stays dumb so its semantics are easy to reason about in tests.
 */
public final class BlockCache {

  private final Cache<BlockKey, byte[]> cache;

  public BlockCache(long maxBytes) {
    this.cache =
        Caffeine.newBuilder()
            .maximumWeight(maxBytes)
            .weigher((BlockKey key, byte[] value) -> value.length)
            .build();
  }

  public byte[] getIfPresent(BlockKey key) {
    return cache.getIfPresent(key);
  }

  public void put(BlockKey key, byte[] block) {
    cache.put(key, block);
  }

  public long estimatedSize() {
    return cache.estimatedSize();
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }
}
