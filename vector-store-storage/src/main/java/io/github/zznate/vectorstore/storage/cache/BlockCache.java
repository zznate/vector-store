package io.github.zznate.vectorstore.storage.cache;

import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide tiered cache of fixed-size object blocks. L1 is an on-heap
 * {@link HeapCacheTier}; an optional L2 {@link L2Provider} (typically the
 * off-heap {@code SlabOffHeapL2Provider}) sits behind L1 and serves
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

  private static final Logger LOG = LoggerFactory.getLogger(BlockCache.class);

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
    if (!useL2 || l2 == null) {
      return tier.get(key).orElse(null);
    }
    return tier.getOrLoad(key, this::loadFromL2OrNull);
  }

  /**
   * L2 read for the {@code getOrLoad} loader. Returns {@code null} on miss
   * <i>and</i> on any L2 runtime failure: a transient L2 fault must not
   * propagate through the caller because Caffeine's single-flight machinery
   * would amplify the throw to every concurrent caller blocked on the
   * same key. Returning {@code null} causes Caffeine to skip caching and
   * the next read retries against L2.
   */
  private byte[] loadFromL2OrNull(BlockKey key) {
    try {
      return l2.get(toL2Key(key)).orElse(null);
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("L2 read failed for key {}: returning null and will refetch", key, e);
      }
      return null;
    }
  }

  public void put(BlockKey key, byte[] block) {
    put(key, block, true);
  }

  /**
   * Insert {@code block} into L1, and into L2 only when {@code useL2} is
   * {@code true}. {@link io.github.zznate.vectorstore.core.cache.CachePolicy#MINIMAL}
   * callers pass {@code false} so cold blocks for those indexes never warm
   * the off-heap tier.
   *
   * <p>If the L2 tier rejects an oversized payload (e.g. cache budget
   * smaller than {@code blockSize}, or any other tier-specific limit
   * exceeded), the L1 tier still holds the entry and the L2 inconsistency
   * surfaces as an {@link IllegalArgumentException} to the caller. In
   * normal block-cache flows this never fires — every payload is exactly
   * {@code blockSize} or smaller — so the exception is a configuration
   * signal, not a runtime path.
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

  /**
   * Evict every cached block whose object key starts with {@code prefix}.
   * Sweeps both L1 ({@link HeapCacheTier#removeIf}) and, when configured, L2
   * ({@link L2Provider#invalidateMatching}).
   *
   * <p>The caller is responsible for {@code /}-bounding {@code prefix} to
   * avoid sibling-key collisions (e.g. {@code seg-xyz} would otherwise also
   * match {@code seg-xyz2}). L2 keys are stored as
   * {@code objectKey + "@" + blockIndex}; {@code prefix} must therefore not
   * contain {@code @} (S3 object keys do not in practice).
   *
   * <p>Idempotent. Eviction counters are not incremented — these are explicit
   * removals, not capacity-driven evictions.
   */
  public void invalidateForObjectKeyPrefix(String prefix) {
    tier.removeIf(bk -> bk.objectKey().startsWith(prefix));
    if (l2 != null) {
      l2.invalidateMatching(s -> s.startsWith(prefix));
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
