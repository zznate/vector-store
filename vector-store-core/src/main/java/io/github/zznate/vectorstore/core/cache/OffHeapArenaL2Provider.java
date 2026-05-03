package io.github.zznate.vectorstore.core.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Off-heap {@link L2Provider} backed by JDK 21's foreign-memory
 * {@link Arena}. Each cached value owns its own shared arena holding a
 * single {@link MemorySegment} sized to the value's bytes; eviction or
 * invalidation closes the arena, releasing the native memory immediately
 * (no GC dependency on heap pressure).
 *
 * <p>Threading: all map / arena lifecycle is serialised by a single
 * {@link ReentrantLock}. {@link #get(String)} returns a fresh byte[] copy
 * of the cached payload, so the caller never observes raw native memory
 * and can outlive the cached entry.
 *
 * <p>FFM (Foreign Function &amp; Memory) is preview in JDK 21 (JEP 442)
 * and final in JDK 22 (JEP 454). The project compiles and runs with
 * {@code --enable-preview} until we upgrade, at which point this class
 * needs no source changes.
 */
@SuppressWarnings("preview")
public final class OffHeapArenaL2Provider implements L2Provider {

  private static final Logger LOG = LoggerFactory.getLogger(OffHeapArenaL2Provider.class);

  public static final String TIER_L2_OFFHEAP = "l2_offheap";
  public static final String DEFAULT_CACHE_NAME = "block";

  private final long maxBytes;
  private final String cacheName;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, OffHeapEntry> entries =
      new LinkedHashMap<>(16, 0.75f, /* accessOrder */ true);
  private final AtomicLong currentBytes = new AtomicLong();

  private final Counter hitCounter;
  private final Counter missCounter;
  private final Counter evictionCounter;

  public OffHeapArenaL2Provider(long maxBytes, MeterRegistry meterRegistry) {
    this(maxBytes, meterRegistry, DEFAULT_CACHE_NAME);
  }

  public OffHeapArenaL2Provider(long maxBytes, MeterRegistry meterRegistry, String cacheName) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
    }
    this.maxBytes = maxBytes;
    this.cacheName = cacheName;

    Tags tags =
        Tags.of(HeapCacheTier.TIER_TAG, TIER_L2_OFFHEAP, HeapCacheTier.CACHE_NAME_TAG, cacheName);
    if (meterRegistry != null) {
      this.hitCounter =
          Counter.builder(HeapCacheTier.METER_HIT)
              .description("Cache hits tagged by tier and cache name")
              .tags(tags)
              .register(meterRegistry);
      this.missCounter =
          Counter.builder(HeapCacheTier.METER_MISS)
              .description("Cache misses tagged by tier and cache name")
              .tags(tags)
              .register(meterRegistry);
      this.evictionCounter =
          Counter.builder(HeapCacheTier.METER_EVICTION)
              .description("Cache evictions tagged by tier and cache name")
              .tags(tags)
              .register(meterRegistry);
      Gauge.builder(HeapCacheTier.METER_BYTES, currentBytes, AtomicLong::get)
          .description("Bytes currently held by the cache tier")
          .tags(tags)
          .strongReference(true)
          .register(meterRegistry);
      Gauge.builder(HeapCacheTier.METER_ENTRIES, entries, m -> (double) m.size())
          .description("Entry count currently held by the cache tier")
          .tags(tags)
          .strongReference(true)
          .register(meterRegistry);
    } else {
      this.hitCounter = null;
      this.missCounter = null;
      this.evictionCounter = null;
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    lock.lock();
    try {
      OffHeapEntry entry = entries.get(key);
      if (entry == null) {
        if (missCounter != null) {
          missCounter.increment();
        }
        return Optional.empty();
      }
      byte[] copy = entry.copyToArray();
      if (hitCounter != null) {
        hitCounter.increment();
      }
      return Optional.of(copy);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void put(String key, byte[] bytes) {
    if (bytes.length > maxBytes) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "L2 cache \"{}\" rejecting oversized put: key={} bytes={} maxBytes={}",
            cacheName,
            key,
            bytes.length,
            maxBytes);
      }
      return;
    }
    lock.lock();
    try {
      OffHeapEntry existing = entries.remove(key);
      if (existing != null) {
        currentBytes.addAndGet(-existing.length());
        existing.close();
      }
      while (currentBytes.get() + bytes.length > maxBytes && !entries.isEmpty()) {
        Iterator<Map.Entry<String, OffHeapEntry>> it = entries.entrySet().iterator();
        Map.Entry<String, OffHeapEntry> oldest = it.next();
        it.remove();
        currentBytes.addAndGet(-oldest.getValue().length());
        oldest.getValue().close();
        if (evictionCounter != null) {
          evictionCounter.increment();
        }
      }
      Arena arena = Arena.ofShared();
      MemorySegment segment = arena.allocate(bytes.length);
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, segment, 0L, bytes.length);
      entries.put(key, new OffHeapEntry(arena, segment, bytes.length));
      currentBytes.addAndGet(bytes.length);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidate(String key) {
    lock.lock();
    try {
      OffHeapEntry entry = entries.remove(key);
      if (entry != null) {
        currentBytes.addAndGet(-entry.length());
        entry.close();
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidateMatching(Predicate<String> keyPredicate) {
    lock.lock();
    try {
      Iterator<Map.Entry<String, OffHeapEntry>> it = entries.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, OffHeapEntry> mapEntry = it.next();
        if (keyPredicate.test(mapEntry.getKey())) {
          it.remove();
          OffHeapEntry entry = mapEntry.getValue();
          currentBytes.addAndGet(-entry.length());
          entry.close();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidateAll() {
    lock.lock();
    try {
      for (OffHeapEntry entry : entries.values()) {
        entry.close();
      }
      entries.clear();
      currentBytes.set(0L);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public CacheTierStats stats() {
    lock.lock();
    try {
      return new CacheTierStats(
          hitCounter == null ? 0 : (long) hitCounter.count(),
          missCounter == null ? 0 : (long) missCounter.count(),
          evictionCounter == null ? 0 : (long) evictionCounter.count(),
          currentBytes.get(),
          maxBytes,
          (long) entries.size());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String tierName() {
    return TIER_L2_OFFHEAP;
  }

  @Override
  public void close() {
    invalidateAll();
  }

  /**
   * One off-heap value: an Arena (lifecycle owner) plus the
   * {@link MemorySegment} it allocated. {@link #length} is the payload
   * size; allocation is exact so {@code segment.byteSize() == length}.
   */
  @SuppressWarnings("preview")
  private record OffHeapEntry(Arena arena, MemorySegment segment, int length) {

    byte[] copyToArray() {
      byte[] buf = new byte[length];
      MemorySegment.copy(segment, 0L, MemorySegment.ofArray(buf), 0L, length);
      return buf;
    }

    void close() {
      try {
        arena.close();
      } catch (RuntimeException e) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("failed to close L2 entry Arena", e);
        }
      }
    }
  }
}
