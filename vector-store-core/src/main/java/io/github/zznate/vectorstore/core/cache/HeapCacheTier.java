package io.github.zznate.vectorstore.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caffeine-backed on-heap implementation of {@link CacheTier}. Supports
 * byte-weighted and count-weighted eviction. Emits the standard
 * {@code vectorstore.cache.*} metrics tagged by {@code tier=l1_heap} and
 * the caller-supplied {@code cache_name} so one dashboard covers every
 * heap cache in the service.
 */
public final class HeapCacheTier<K, V> implements CacheTier<K, V> {

  private static final Logger LOG = LoggerFactory.getLogger(HeapCacheTier.class);

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
  private final AtomicLong currentBytes = new AtomicLong();
  private final long maxBytes;

  private HeapCacheTier(Builder<K, V> b) {
    this.cacheName = b.cacheName;
    this.weigher = b.weigher;
    this.maxBytes = b.maxBytes;

    Tags tags = Tags.of(Tag.of(TIER_TAG, TIER_L1_HEAP), Tag.of(CACHE_NAME_TAG, b.cacheName));

    Counter[] counters = registerCounters(b.meterRegistry, tags);
    this.hitCounter = counters[0];
    this.missCounter = counters[1];
    this.evictionCounter = counters[2];

    this.cache = buildCaffeine(b);

    registerGauges(b.meterRegistry, tags);
  }

  private static Counter[] registerCounters(MeterRegistry meterRegistry, Tags tags) {
    if (meterRegistry == null) {
      return new Counter[3];
    }
    return new Counter[] {
      Counter.builder(METER_HIT)
          .description("Cache hits tagged by tier and cache name")
          .tags(tags)
          .register(meterRegistry),
      Counter.builder(METER_MISS)
          .description("Cache misses tagged by tier and cache name")
          .tags(tags)
          .register(meterRegistry),
      Counter.builder(METER_EVICTION)
          .description("Cache evictions tagged by tier and cache name")
          .tags(tags)
          .register(meterRegistry),
    };
  }

  private Cache<K, V> buildCaffeine(Builder<K, V> b) {
    Caffeine<Object, Object> c =
        Caffeine.newBuilder().recordStats().scheduler(Scheduler.systemScheduler());
    if (b.weigher != null && b.maxBytes > 0) {
      ToIntFunction<V> w = b.weigher;
      c.maximumWeight(b.maxBytes).weigher((K key, V value) -> w.applyAsInt(value));
    } else if (b.maxEntries > 0) {
      c.maximumSize(b.maxEntries);
    }
    Counter localEviction = this.evictionCounter;
    BiConsumer<K, V> userRemovalHook = b.removalHook;
    c.evictionListener((K key, V value, RemovalCause cause) -> onEviction(cause, localEviction));
    c.removalListener(
        (K key, V value, RemovalCause cause) -> onRemoval(key, value, userRemovalHook));
    return c.build();
  }

  /**
   * Synchronous: fires inside the eviction with the cache lock held, giving
   * happens-before for any caller that wants to observe the eviction-completed
   * state (e.g. an L1→L2 promotion observer). Only fires when
   * {@link RemovalCause#wasEvicted()} is true.
   */
  private void onEviction(RemovalCause cause, Counter localEviction) {
    if (cause.wasEvicted() && localEviction != null) {
      localEviction.increment();
    }
  }

  /**
   * Asynchronous: fires for every removal cause (including {@code EXPLICIT}),
   * dispatched on Caffeine's executor. Carries the byte-accounting decrement
   * — keeping it here ensures explicit {@code invalidate} / {@code removeIf}
   * paths still tick the gauge — and runs the user cleanup hook.
   */
  private void onRemoval(K key, V value, BiConsumer<K, V> userHook) {
    if (value != null && weigher != null) {
      currentBytes.addAndGet(-weigher.applyAsInt(value));
    }
    if (userHook != null && value != null) {
      runUserRemovalHook(key, value, userHook);
    }
  }

  private void runUserRemovalHook(K key, V value, BiConsumer<K, V> userHook) {
    try {
      userHook.accept(key, value);
    } catch (RuntimeException e) {
      // Hook is best-effort; a failing cleanup must not cascade into
      // cache corruption. Log so the operator sees the stack trace even
      // though we recover.
      if (LOG.isWarnEnabled()) {
        LOG.warn("cache \"{}\" removal hook failed for key {}", cacheName, key, e);
      }
    }
  }

  private void registerGauges(MeterRegistry meterRegistry, Tags tags) {
    if (meterRegistry == null) {
      return;
    }
    Gauge.builder(METER_BYTES, currentBytes, AtomicLong::get)
        .description("Current bytes held by the cache tier")
        .tags(tags)
        .strongReference(true)
        .register(meterRegistry);
    Gauge.builder(METER_ENTRIES, cache, Cache::estimatedSize)
        .description("Current entry count held by the cache tier")
        .tags(tags)
        .strongReference(true)
        .register(meterRegistry);
  }

  @Override
  public Optional<V> get(K key) {
    V value = cache.getIfPresent(key);
    if (value == null) {
      if (missCounter != null) {
        missCounter.increment();
      }
      return Optional.empty();
    }
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

  /**
   * Remove every entry whose key matches {@code keyPredicate}. The
   * removal listener fires for each matched entry, so byte accounting
   * and any user-supplied cleanup hook run normally.
   */
  public void removeIf(Predicate<K> keyPredicate) {
    cache.asMap().keySet().removeIf(keyPredicate);
  }

  @Override
  public CacheTierStats stats() {
    CacheStats s = cache.stats();
    return new CacheTierStats(
        s.hitCount(),
        s.missCount(),
        s.evictionCount(),
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
    private BiConsumer<K, V> removalHook;

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

    /**
     * Invoked by the cache when an entry leaves — evicted, invalidated, or
     * replaced. Useful for closing resources held by the value (file
     * handles, native memory). Exceptions thrown by the hook are
     * swallowed; the hook is expected to self-log.
     */
    public Builder<K, V> onRemoval(BiConsumer<K, V> hook) {
      this.removalHook = hook;
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
