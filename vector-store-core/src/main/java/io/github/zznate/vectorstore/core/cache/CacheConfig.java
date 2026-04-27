package io.github.zznate.vectorstore.core.cache;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed binding for every {@code vectorstore.cache.*} configuration key.
 * Owns the budgets and tunables for the four warm-tier caches:
 *
 * <ul>
 *   <li>{@link #block()} — the object-store block cache (L1 heap + optional
 *       L2 off-heap arena).
 *   <li>{@link #sidecar()} — per-segment metadata sidecar cache (attribute
 *       maps, tombstone bitmaps).
 *   <li>{@link #manifest()} — version-keyed manifest cache backing
 *       {@code ManifestCache}.
 *   <li>{@link #segmentHandle()} — bounded handle cache backing
 *       {@code SegmentHandleCache}.
 * </ul>
 *
 * <p>Each consumer reads the slice it owns directly from this mapping;
 * there is no central pool today, so per-cache budgets stay independent.
 * A future global L1/L2 pool can subsume these slices behind the same
 * config tree without breaking the property keys.
 */
@ConfigMapping(prefix = "vectorstore.cache")
public interface CacheConfig {

  BlockConfig block();

  SidecarConfig sidecar();

  ManifestConfig manifest();

  @WithName("segment-handle")
  SegmentHandleConfig segmentHandle();

  /** Block cache (L1 heap + optional L2 off-heap arena). */
  interface BlockConfig {

    /** L1 byte budget. Default 64 MiB. */
    @WithDefault("67108864")
    long bytes();

    /** Fixed block size in bytes used for caching and alignment. Default 64 KiB. */
    @WithName("block-size")
    @WithDefault("65536")
    int blockSize();

    L2Config l2();

    interface L2Config {

      /** Enable the off-heap arena tier behind L1. Disabled by default. */
      @WithDefault("false")
      boolean enabled();

      /** L2 byte budget. Default 256 MiB. Ignored when {@link #enabled()} is false. */
      @WithDefault("268435456")
      long bytes();
    }
  }

  /** Sidecar cache (attribute maps + tombstone bitmaps). */
  interface SidecarConfig {

    /** L1 byte budget. Default 128 MiB. */
    @WithDefault("134217728")
    long bytes();
  }

  /** Manifest cache (active-segment lists keyed by manifest version). */
  interface ManifestConfig {

    /** Maximum number of cached manifest entries. Default 64. */
    @WithName("max-entries")
    @WithDefault("64")
    int maxEntries();

    /**
     * TTL for the {@code currentVersion} probe in nanoseconds. Defaults to
     * 100 ms so back-to-back queries amortise the catalog round-trip while
     * a freshly-committed version still becomes visible within a handful
     * of milliseconds.
     */
    @WithName("version-ttl-nanos")
    @WithDefault("100000000")
    long versionTtlNanos();
  }

  /** Segment-handle cache (loaded JVector graph + ordinal map per segment). */
  interface SegmentHandleConfig {

    /** Maximum number of cached handles. Default 256. */
    @WithName("max-entries")
    @WithDefault("256")
    int maxEntries();
  }
}
