package io.github.zznate.vectorstore.metadata.sidecar;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Process-wide cache of parsed sidecar objects. Byte-weighted eviction
 * (default 128 MiB) is enforced across every segment's sidecars; both
 * attributes and tombstones share the same LRU budget, so a workload that
 * touches many segments but only reads tombstones stays within the
 * configured heap ceiling.
 *
 * <p>Keys use {@link #attributesKey} / {@link #tombstonesKey} so two
 * kinds of sidecar from the same segment don't collide. The cache is
 * deliberately dumb; readers own hit / miss semantics and loading.
 */
public final class SidecarCache {

  private final Cache<String, CachedSidecar> cache;

  public SidecarCache(long maxBytes) {
    this.cache =
        Caffeine.newBuilder()
            .maximumWeight(maxBytes)
            .weigher(
                (String key, CachedSidecar value) ->
                    (int) Math.min(Integer.MAX_VALUE, value.sizeBytes()))
            .build();
  }

  public static String attributesKey(String segmentId) {
    return segmentId + ":attributes";
  }

  public static String tombstonesKey(String segmentId) {
    return segmentId + ":tombstones";
  }

  public CachedSidecar getIfPresent(String key) {
    return cache.getIfPresent(key);
  }

  public void put(String key, CachedSidecar value) {
    cache.put(key, value);
  }

  public void invalidate(String key) {
    cache.invalidate(key);
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }

  public long estimatedSize() {
    return cache.estimatedSize();
  }
}
