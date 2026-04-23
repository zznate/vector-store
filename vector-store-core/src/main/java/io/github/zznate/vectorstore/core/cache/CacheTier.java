package io.github.zznate.vectorstore.core.cache;

import java.util.Optional;

/**
 * Generic, typed cache tier. Implementations emit a consistent set of
 * {@code vectorstore.cache.*} metrics tagged by {@code tier} and
 * {@code cache_name}, so a single operator dashboard covers every cache
 * surface the service exposes.
 *
 * <p>On-heap tiers hold values by reference ({@code V} is whatever the
 * caller wants — byte arrays, parsed records, materialised handles). The
 * companion {@link L2Provider} interface covers byte-only tiers that
 * cannot store live object references (off-heap arenas, mmap'd disk
 * files, future remote providers).
 *
 * <p>A {@link CacheTier} is safe for concurrent access; implementations
 * serialise internally where needed.
 *
 * @param <K> key type; must have stable {@code equals} / {@code hashCode}.
 * @param <V> value type.
 */
public interface CacheTier<K, V> {

  /** Return the cached value for {@code key}, or empty on miss. */
  Optional<V> get(K key);

  /**
   * Associate {@code value} with {@code key}. If the tier is bounded by
   * bytes, the implementation's weigher computes the weight from the
   * value. Over-budget puts trigger eviction.
   */
  void put(K key, V value);

  /** Remove {@code key} from the cache if present. */
  void invalidate(K key);

  /** Remove every entry. */
  void invalidateAll();

  /** Snapshot of aggregate counters and size. */
  CacheTierStats stats();

  /** Operator-facing cache name used as the {@code cache_name} metric tag. */
  String cacheName();
}
