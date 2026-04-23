package io.github.zznate.vectorstore.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToIntFunction;

/**
 * Caffeine-backed on-heap implementation of {@link CacheTier}. Supports
 * byte-weighted and count-weighted eviction. Emits the standard
 * {@code vectorstore.cache.*} metrics tagged by {@code tier=l1_heap} and
 * the caller-supplied {@code cache_name} so one dashboard covers every
 * heap cache in the service.
 */
public final class HeapCacheTier<K, V> implements CacheTier<K, V> {

  public static final String TIER_TAG = "tier";
  public static final String CACHE_NAME_TAG = "cache_name";
  public static final String TIER_L1_HEAP = "l1_heap";

  public static final String METER_HIT = "vectorstore.cache.hit";
  public static final String METER_MISS = "vectorstore.cache.miss";
  public static final String METER_EVICTION = "vectorstore.cache.eviction";
  public static final String METER_BYTES = "vectorstore.cache.bytes.current";
  public static final String METER_ENTRIES = "vectorstore.cache.entries.current";

  private final Cache<K, V> cache;
  private final String cacheName;
  private final ToIntFunction<V> weigher;
  private final Counter hitCounter;
  private final Counter missCounter;
  private final Counter evictionCounter;
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder evictions = new LongAdder();
  private final AtomicLong currentBytes = new AtomicLong();
  private final long maxBytes;

  private HeapCacheTier(Builder<K, V> b) {
    this.cacheName = b.cacheName;
    this.weigher = b.weigher;
    this.maxBytes = b.maxBytes;

    Tags tags = Tags.of(Tag.of(TIER_TAG, TIER_L1_HEAP), Tag.of(CACHE_NAME_TAG, b.cacheName));

    if (b.meterRegistry != null) {
      this.hitCounter =
          Counter.builder(METER_HIT)
              .description("Cache hits tagged by tier and cache name")
              .tags(tags)
              .register(b.meterRegistry);
      this.missCounter =
          Counter.builder(METER_MISS)
              .description("Cache misses tagged by tier and cache name")
              .tags(tags)
              .register(b.meterRegistry);
      this.evictionCounter =
          Counter.builder(METER_EVICTION)
              .description("Cache evictions tagged by tier and cache name")
              .tags(tags)
              .register(b.meterRegistry);
    } else {
      this.hitCounter = null;
      this.missCounter = null;
      this.evictionCounter = null;
    }

    Caffeine<Object, Object> c = Caffeine.newBuilder();
    if (b.weigher != null && b.maxBytes > 0) {
      ToIntFunction<V> w = b.weigher;
      c.maximumWeight(b.maxBytes).weigher((K key, V value) -> w.applyAsInt(value));
    } else if (b.maxEntries > 0) {
      c.maximumSize(b.maxEntries);
    }
    Counter localEviction = this.evictionCounter;
    c.removalListener(
        (K key, V value, RemovalCause cause) -> {
          if (value != null && weigher != null) {
            currentBytes.addAndGet(-weigher.applyAsInt(value));
          }
          if (cause.wasEvicted()) {
            evictions.increment();
            if (localEviction != null) {
              localEviction.increment();
            }
          }
        });
    this.cache = c.build();

    if (b.meterRegistry != null) {
      Gauge.builder(METER_BYTES, currentBytes, AtomicLong::get)
          .description("Current bytes held by the cache tier")
          .tags(tags)
          .strongReference(true)
          .register(b.meterRegistry);
      Gauge.builder(METER_ENTRIES, cache, c2 -> c2.estimatedSize())
          .description("Current entry count held by the cache tier")
          .tags(tags)
          .strongReference(true)
          .register(b.meterRegistry);
    }
  }

  @Override
  public Optional<V> get(K key) {
    V value = cache.getIfPresent(key);
    if (value == null) {
      misses.increment();
      if (missCounter != null) {
        missCounter.increment();
      }
      return Optional.empty();
    }
    hits.increment();
    if (hitCounter != null) {
      hitCounter.increment();
    }
    return Optional.of(value);
  }

  @Override
  public void put(K key, V value) {
    if (weigher != null) {
      currentBytes.addAndGet(weigher.applyAsInt(value));
    }
    cache.put(key, value);
  }

  @Override
  public void invalidate(K key) {
    cache.invalidate(key);
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
  }

  @Override
  public CacheTierStats stats() {
    return new CacheTierStats(
        hits.sum(),
        misses.sum(),
        evictions.sum(),
        weigher == null ? -1L : currentBytes.get(),
        maxBytes,
        cache.estimatedSize());
  }

  @Override
  public String cacheName() {
    return cacheName;
  }

  public static <K, V> Builder<K, V> builder(String cacheName) {
    return new Builder<>(cacheName);
  }

  public static final class Builder<K, V> {
    private final String cacheName;
    private long maxBytes;
    private long maxEntries;
    private ToIntFunction<V> weigher;
    private MeterRegistry meterRegistry;

    private Builder(String cacheName) {
      this.cacheName = cacheName;
    }

    public Builder<K, V> byteWeighted(long maxBytes, ToIntFunction<V> weigher) {
      this.maxBytes = maxBytes;
      this.weigher = weigher;
      this.maxEntries = 0;
      return this;
    }

    public Builder<K, V> countWeighted(long maxEntries) {
      this.maxEntries = maxEntries;
      this.maxBytes = 0;
      this.weigher = null;
      return this;
    }

    public Builder<K, V> meterRegistry(MeterRegistry meterRegistry) {
      this.meterRegistry = meterRegistry;
      return this;
    }

    public HeapCacheTier<K, V> build() {
      if (maxBytes <= 0 && maxEntries <= 0) {
        throw new IllegalStateException(
            "HeapCacheTier \"" + cacheName + "\" requires a byte or entry budget");
      }
      return new HeapCacheTier<>(this);
    }
  }
}
