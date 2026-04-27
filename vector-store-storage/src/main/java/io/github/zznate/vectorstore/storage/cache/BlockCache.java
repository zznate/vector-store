package io.github.zznate.vectorstore.storage.cache;

import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Process-wide tiered cache of fixed-size object blocks. L1 is an on-heap
 * {@link HeapCacheTier}; an optional L2 {@link L2Provider} (typically the
 * off-heap {@code OffHeapArenaL2Provider}) sits behind L1 and serves
 * cold-on-heap reads without an object-store round trip. Writes are
 * write-through: every {@link #put(BlockKey, byte[])} populates both
 * tiers when L2 is configured.
 *
 * <p>Metrics flow through each tier's standard {@code vectorstore.cache.*}
 * meters; both tiers carry {@code cache_name=block} and differ only on
 * the {@code tier} tag ({@code l1_heap} vs {@code l2_offheap}).
 *
 * <p>The existing API (nullable {@code getIfPresent}, void {@code put})
 * is preserved so call sites are untouched.
 */
public final class BlockCache {

  public static final String CACHE_NAME = "block";

  private final HeapCacheTier<BlockKey, byte[]> tier;
  private final L2Provider l2;

  public BlockCache(long maxBytes, MeterRegistry meterRegistry) {
    this(maxBytes, meterRegistry, null);
  }

  public BlockCache(long maxBytes, MeterRegistry meterRegistry, L2Provider l2) {
    this.tier =
        HeapCacheTier.<BlockKey, byte[]>builder(CACHE_NAME)
            .byteWeighted(maxBytes, v -> v.length)
            .meterRegistry(meterRegistry)
            .build();
    this.l2 = l2;
  }

  public byte[] getIfPresent(BlockKey key) {
    return getIfPresent(key, true);
  }

  /**
   * Look up {@code key}, optionally bypassing the L2 tier. {@code useL2=false}
   * is the path taken by {@link io.github.zznate.vectorstore.core.cache.CachePolicy#MINIMAL}
   * indexes, which neither read nor promote off-heap blocks.
   */
  public byte[] getIfPresent(BlockKey key, boolean useL2) {
    byte[] hot = tier.get(key).orElse(null);
    if (hot != null) {
      return hot;
    }
    if (!useL2 || l2 == null) {
      return null;
    }
    byte[] warm = l2.get(toL2Key(key)).orElse(null);
    if (warm != null) {
      // Promote so the next read of this block stays on the heap path.
      tier.put(key, warm);
    }
    return warm;
  }

  public void put(BlockKey key, byte[] block) {
    put(key, block, true);
  }

  /**
   * Insert {@code block} into L1, and into L2 only when {@code useL2} is
   * {@code true}. {@link io.github.zznate.vectorstore.core.cache.CachePolicy#MINIMAL}
   * callers pass {@code false} so cold blocks for those indexes never warm
   * the off-heap tier.
   */
  public void put(BlockKey key, byte[] block, boolean useL2) {
    tier.put(key, block);
    if (useL2 && l2 != null) {
      l2.put(toL2Key(key), block);
    }
  }

  public long estimatedSize() {
    return tier.stats().currentEntries();
  }

  public void invalidateAll() {
    tier.invalidateAll();
    if (l2 != null) {
      l2.invalidateAll();
    }
  }

  /** Access the underlying L1 tier for stats reporting. */
  public HeapCacheTier<BlockKey, byte[]> tier() {
    return tier;
  }

  /** Underlying L2 provider, or {@code null} when off-heap is disabled. */
  public L2Provider l2() {
    return l2;
  }

  static String toL2Key(BlockKey key) {
    return key.objectKey() + "@" + key.blockIndex();
  }
}
