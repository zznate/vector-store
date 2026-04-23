package io.github.zznate.vectorstore.storage.cache;

import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Process-wide on-heap cache of fixed-size object blocks. Implemented as a
 * thin façade over {@link HeapCacheTier} so every heap cache in the
 * service emits the same {@code vectorstore.cache.*} metrics tagged by
 * {@code tier=l1_heap} and {@code cache_name=block}.
 *
 * <p>The existing API (nullable {@code getIfPresent}, void {@code put})
 * is preserved so call sites are untouched.
 */
public final class BlockCache {

  public static final String CACHE_NAME = "block";

  private final HeapCacheTier<BlockKey, byte[]> tier;

  public BlockCache(long maxBytes, MeterRegistry meterRegistry) {
    this.tier =
        HeapCacheTier.<BlockKey, byte[]>builder(CACHE_NAME)
            .byteWeighted(maxBytes, v -> v.length)
            .meterRegistry(meterRegistry)
            .build();
  }

  public byte[] getIfPresent(BlockKey key) {
    return tier.get(key).orElse(null);
  }

  public void put(BlockKey key, byte[] block) {
    tier.put(key, block);
  }

  public long estimatedSize() {
    return tier.stats().currentEntries();
  }

  public void invalidateAll() {
    tier.invalidateAll();
  }

  /** Access the underlying tier for stats reporting. */
  public HeapCacheTier<BlockKey, byte[]> tier() {
    return tier;
  }
}
