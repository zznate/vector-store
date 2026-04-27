package io.github.zznate.vectorstore.core.catalog.manifest;

import io.github.zznate.vectorstore.core.cache.CacheConfig;
import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process cache fronting {@link ManifestResolver}. Manifest rows are
 * append-only in the catalog, so a cache entry keyed by
 * {@code (indexId, version)} is correct for the life of that version — it
 * never needs invalidation. Older versions are simply evicted by LRU.
 *
 * <p>A second, tiny TTL cache sits over the {@code currentVersion} probe
 * so back-to-back queries on the same index don't pay two catalog
 * round-trips per query. The TTL is short ({@link #DEFAULT_VERSION_TTL_NANOS})
 * so a freshly-committed version still becomes visible within a handful
 * of milliseconds even if the committer did not explicitly populate the
 * cache via {@link #populate}.
 *
 * <p>Writers (commit, compaction) call {@link #populate} after the
 * manifest-version append to seed the cache with the exact new state,
 * which bypasses the TTL for the just-written index.
 */
@ApplicationScoped
public class ManifestCache {

  public static final String CACHE_NAME = "manifest";
  public static final int DEFAULT_MAX_ENTRIES = 64;
  public static final long DEFAULT_VERSION_TTL_NANOS = 100_000_000L; // 100 ms

  private final ManifestResolver resolver;
  private final HeapCacheTier<ManifestKey, List<Segment>> tier;
  private final ConcurrentMap<String, VersionEntry> versionCache = new ConcurrentHashMap<>();
  private final long versionTtlNanos;

  @Inject
  public ManifestCache(
      ManifestResolver resolver, MeterRegistry meterRegistry, CacheConfig config) {
    this(
        resolver,
        meterRegistry,
        config.manifest().maxEntries(),
        config.manifest().versionTtlNanos());
  }

  public ManifestCache(
      ManifestResolver resolver,
      MeterRegistry meterRegistry,
      int maxEntries,
      long versionTtlNanos) {
    this.resolver = resolver;
    this.versionTtlNanos = versionTtlNanos;
    this.tier =
        HeapCacheTier.<ManifestKey, List<Segment>>builder(CACHE_NAME)
            .countWeighted(maxEntries)
            .meterRegistry(meterRegistry)
            .build();
  }

  /** Active segments for {@code indexId}, served from cache on hit. */
  public List<Segment> activeSegments(String indexId) {
    Optional<Integer> version = currentVersion(indexId);
    if (version.isEmpty()) {
      return List.of();
    }
    ManifestKey key = new ManifestKey(indexId, version.get());
    Optional<List<Segment>> cached = tier.get(key);
    if (cached.isPresent()) {
      return cached.get();
    }
    List<Segment> segments = resolver.activeSegments(indexId);
    tier.put(key, segments);
    return segments;
  }

  /** Current manifest version for {@code indexId}, with a short TTL cache. */
  public Optional<Integer> currentVersion(String indexId) {
    VersionEntry cached = versionCache.get(indexId);
    long now = System.nanoTime();
    if (cached != null && now - cached.readAtNanos() < versionTtlNanos) {
      return cached.isEmpty() ? Optional.empty() : Optional.of(cached.version());
    }
    Optional<Integer> fresh = resolver.currentVersion(indexId);
    versionCache.put(indexId, new VersionEntry(fresh.orElse(-1), now));
    return fresh;
  }

  /**
   * Seed the cache with the manifest state produced by a successful
   * writer (commit or compaction). Bypasses the TTL so queries see the
   * new state immediately on the next access.
   */
  public void populate(String indexId, int version, List<Segment> segments) {
    tier.put(new ManifestKey(indexId, version), List.copyOf(segments));
    versionCache.put(indexId, new VersionEntry(version, System.nanoTime()));
  }

  /**
   * Drop the cached current-version probe for {@code indexId}. The
   * version-keyed manifest entries stay — they are still correct for their
   * version.
   */
  public void invalidate(String indexId) {
    versionCache.remove(indexId);
  }

  /**
   * Remove every entry for {@code indexId} — version probe and every
   * version-keyed manifest entry. Called when an index is deleted or
   * otherwise structurally invalidated so a later index with the same ID
   * doesn't serve from a predecessor's state.
   */
  public void invalidateIndex(String indexId) {
    versionCache.remove(indexId);
    tier.removeIf(key -> key.indexId().equals(indexId));
  }

  /** Wipe everything. Used by test setup and bulk maintenance. */
  public void invalidateAll() {
    versionCache.clear();
    tier.invalidateAll();
  }

  /** Underlying tier for stats reporting and test assertions. */
  public HeapCacheTier<ManifestKey, List<Segment>> tier() {
    return tier;
  }

  public record ManifestKey(String indexId, int version) {}

  private record VersionEntry(int version, long readAtNanos) {
    boolean isEmpty() {
      return version < 0;
    }
  }
}
