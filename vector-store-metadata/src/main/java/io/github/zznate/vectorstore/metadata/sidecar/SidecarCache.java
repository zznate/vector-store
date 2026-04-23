package io.github.zznate.vectorstore.metadata.sidecar;

import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Process-wide cache of parsed sidecar objects. Thin façade over a
 * {@link HeapCacheTier} so both attribute and tombstone sidecars share a
 * single byte-weighted LRU budget while emitting the standard
 * {@code vectorstore.cache.*} metrics tagged by
 * {@code tier=l1_heap} and {@code cache_name=sidecar}.
 *
 * <p>Keys use {@link #attributesKey} / {@link #tombstonesKey} so two
 * kinds of sidecar from the same segment don't collide.
 */
public final class SidecarCache {

  public static final String CACHE_NAME = "sidecar";

  private final HeapCacheTier<String, CachedSidecar> tier;

  public SidecarCache(long maxBytes, MeterRegistry meterRegistry) {
    this.tier =
        HeapCacheTier.<String, CachedSidecar>builder(CACHE_NAME)
            .byteWeighted(
                maxBytes, value -> (int) Math.min(Integer.MAX_VALUE, value.sizeBytes()))
            .meterRegistry(meterRegistry)
            .build();
  }

  public static String attributesKey(String segmentId) {
    return segmentId + ":attributes";
  }

  public static String tombstonesKey(String segmentId) {
    return segmentId + ":tombstones";
  }

  public CachedSidecar getIfPresent(String key) {
    return tier.get(key).orElse(null);
  }

  public void put(String key, CachedSidecar value) {
    tier.put(key, value);
  }

  public void invalidate(String key) {
    tier.invalidate(key);
  }

  public void invalidateAll() {
    tier.invalidateAll();
  }

  public long estimatedSize() {
    return tier.stats().currentEntries();
  }

  /** Access the underlying tier for stats reporting. */
  public HeapCacheTier<String, CachedSidecar> tier() {
    return tier;
  }
}
