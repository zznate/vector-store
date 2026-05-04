package io.github.zznate.vectorstore.core.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Off-heap {@link L2Provider} backed by a slab allocator: one shared
 * {@link Arena} carves out fixed-size slots distributed across
 * {@value #SHARDS} shards, each with its own {@link ReentrantLock},
 * access-ordered LRU map, and free-slot pool. Cached payloads are
 * fixed at {@code blockSize}; trailing payloads shorter than
 * {@code blockSize} are stored in a full slot and read back at their
 * actual length via the per-entry length field.
 *
 * <p><b>Threading.</b> Reads and writes acquire the per-shard lock
 * for the duration of the {@link MemorySegment#copy} so concurrent
 * slot reuse cannot tear bytes. Eight shards bound the contention
 * surface; readers on disjoint shards run independently.
 *
 * <p><b>Bookkeeping ordering.</b> Every put follows a four-step
 * shape inside the shard lock: plan eviction read-only, apply
 * evictions to vacate slots, copy bytes into a freshly-allocated
 * slot, then insert the new entry into the LRU. A failed copy is
 * isolated by the orphan-recovery branch: the destination slot
 * returns to the free pool before the exception propagates, leaving
 * the LRU and free pool consistent. No slot referenced by the LRU is
 * ever the destination of an in-flight copy.
 *
 * <p><b>Eviction trigger.</b> The eviction loop runs while either
 * byte pressure (post-put bytes &gt; per-shard soft cap) or slot
 * pressure (no free slot would remain post-eviction) holds.
 * Trailing-block-heavy workloads can fill the slot pool without
 * reaching the byte cap; the dual predicate handles that case.
 *
 * <p><b>Lifecycle.</b> The shared Arena is closed once at
 * {@link #close()}, deterministically unmapping every segment. Use
 * after close throws {@link IllegalStateException} from every entry
 * point.
 *
 * <p>FFM (Foreign Function &amp; Memory) is preview in JDK 21 (JEP
 * 442) and final in JDK 22 (JEP 454). The project compiles and runs
 * with {@code --enable-preview} until we upgrade.
 */
@SuppressWarnings({"preview", "PMD.CyclomaticComplexity"})
public final class SlabOffHeapL2Provider implements L2Provider {

  private static final Logger LOG = LoggerFactory.getLogger(SlabOffHeapL2Provider.class);

  public static final String TIER_L2_OFFHEAP = "l2_offheap";
  public static final String DEFAULT_CACHE_NAME = "block";

  private static final int SHARDS = 8;
  private static final long SEGMENT_BYTES = 16L * 1024 * 1024;
  private static final double SOFT_CAP_FRACTION = 0.95;

  private final long maxBytes;
  private final int blockSize;
  private final long perShardSoftCap;
  private final String cacheName;
  private final Arena arena;
  private final MemorySegment[] segments;
  private final long slotsPerSegment;
  private final long totalSlots;
  private final Shard[] shards;

  private final Counter hitCounter;
  private final Counter missCounter;
  private final Counter evictionCounter;
  private final MeterRegistry meterRegistry;
  private final List<Meter> registeredMeters = new ArrayList<>();

  private volatile boolean closed;

  public SlabOffHeapL2Provider(long maxBytes, int blockSize, MeterRegistry meterRegistry) {
    this(maxBytes, blockSize, meterRegistry, DEFAULT_CACHE_NAME);
  }

  public SlabOffHeapL2Provider(
      long maxBytes, int blockSize, MeterRegistry meterRegistry, String cacheName) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
    }
    if (blockSize <= 0) {
      throw new IllegalArgumentException("blockSize must be > 0, got " + blockSize);
    }
    long segSize = Math.min(SEGMENT_BYTES, maxBytes);
    if (blockSize > segSize) {
      throw new IllegalArgumentException(
          "blockSize " + blockSize + " exceeds per-segment size " + segSize);
    }
    if (maxBytes < (long) SHARDS * blockSize) {
      throw new IllegalArgumentException(
          "maxBytes " + maxBytes + " too small for " + SHARDS + " shards of blockSize "
              + blockSize + " (need at least " + ((long) SHARDS * blockSize) + ")");
    }
    this.maxBytes = maxBytes;
    this.blockSize = blockSize;
    this.cacheName = cacheName;

    this.arena = Arena.ofShared();
    int segCount = (int) Math.ceil((double) maxBytes / segSize);
    this.segments = new MemorySegment[segCount];
    for (int i = 0; i < segCount; i++) {
      segments[i] = arena.allocate(segSize);
    }
    this.slotsPerSegment = segSize / blockSize;
    this.totalSlots = (long) segCount * slotsPerSegment;

    this.shards = new Shard[SHARDS];
    long slotsPerShard = totalSlots / SHARDS;
    for (int i = 0; i < SHARDS; i++) {
      shards[i] = new Shard();
      long lo = i * slotsPerShard;
      long hi = (i == SHARDS - 1) ? totalSlots : (i + 1) * slotsPerShard;
      for (long s = lo; s < hi; s++) {
        shards[i].freeSlots.addLast(s);
      }
    }

    this.perShardSoftCap = (long) ((double) (maxBytes / SHARDS) * SOFT_CAP_FRACTION);
    this.meterRegistry = meterRegistry;

    Tags tags =
        Tags.of(HeapCacheTier.TIER_TAG, TIER_L2_OFFHEAP, HeapCacheTier.CACHE_NAME_TAG, cacheName);
    this.hitCounter =
        newCounter(meterRegistry, HeapCacheTier.METER_HIT, "Cache hits tagged by tier and cache name", tags);
    this.missCounter =
        newCounter(meterRegistry, HeapCacheTier.METER_MISS, "Cache misses tagged by tier and cache name", tags);
    this.evictionCounter =
        newCounter(meterRegistry, HeapCacheTier.METER_EVICTION, "Cache evictions tagged by tier and cache name", tags);
    registerGauges(meterRegistry, tags);
  }

  @Override
  public Optional<byte[]> get(String key) {
    ensureOpen();
    Shard s = shards[shard(key)];
    s.lock.lock();
    try {
      BlockEntry e = s.entries.get(key);
      if (e == null) {
        if (missCounter != null) {
          missCounter.increment();
        }
        return Optional.empty();
      }
      byte[] copy = new byte[e.length()];
      MemorySegment.copy(
          segmentFor(e.slot()), offsetInSegment(e.slot()),
          MemorySegment.ofArray(copy), 0L,
          e.length());
      if (hitCounter != null) {
        hitCounter.increment();
      }
      return Optional.of(copy);
    } finally {
      s.lock.unlock();
    }
  }

  @Override
  public void put(String key, byte[] bytes) {
    ensureOpen();
    if (bytes.length > blockSize) {
      throw new IllegalArgumentException(
          "L2 off-heap cache \""
              + cacheName
              + "\" rejecting oversized put: key="
              + key
              + " bytes="
              + bytes.length
              + " blockSize="
              + blockSize);
    }
    Shard s = shards[shard(key)];
    s.lock.lock();
    try {
      PutPlan plan = planPut(s, key, bytes.length);
      applyEvictions(s, plan);
      writeBytesToSlot(s, plan, key, bytes);
      applyNewEntry(s, plan, key, bytes.length);
    } finally {
      s.lock.unlock();
    }
  }

  /**
   * Read-only planning: determine which entries to evict so the post-put
   * state holds both byte-budget and slot-pool invariants. No mutation
   * of {@code s.entries}, {@code s.freeSlots}, or {@code s.currentBytes}.
   */
  private PutPlan planPut(Shard s, String key, int putLen) {
    BlockEntry existing = s.entries.get(key);
    long existingLen = 0L;
    long existingSlot = 0L;
    if (existing != null) {
      existingLen = existing.length();
      existingSlot = existing.slot();
    }
    long projected = s.currentBytes.get() - existingLen + putLen;
    if (projected <= perShardSoftCap && !s.freeSlots.isEmpty()) {
      // Hot-path fast exit: byte budget OK and a free slot is already
      // in freeSlots. Skip the ArrayList + LinkedHashMap iterator
      // allocation entirely. The existing entry's slot does not count
      // toward "available" here — applyNewEntry frees it only after
      // writeBytesToSlot has popped a fresh slot, so a put that lands
      // on a saturated shard with all slots in `entries` must evict
      // a different entry to free a slot for the persistent copy.
      return new PutPlan(existing, existingLen, existingSlot, List.of(), projected);
    }
    List<Map.Entry<String, BlockEntry>> toEvict = new ArrayList<>(2); // typical 1, headroom for 2
    Iterator<Map.Entry<String, BlockEntry>> it = s.entries.entrySet().iterator();
    while ((projected > perShardSoftCap || s.freeSlots.size() + toEvict.size() == 0)
        && it.hasNext()) {
      Map.Entry<String, BlockEntry> oldest = it.next();
      if (oldest.getKey().equals(key)) {
        continue;
      }
      toEvict.add(Map.entry(oldest.getKey(), oldest.getValue()));
      projected -= oldest.getValue().length();
    }
    return new PutPlan(existing, existingLen, existingSlot, toEvict, projected);
  }

  /**
   * Vacate slots before the persistent copy so the destination pulled in
   * step 3 cannot collide with any LRU-referenced slot. Removed entries
   * are no longer reachable as cache hits; their slot bytes are
   * unreferenced until a future put overwrites them.
   */
  private void applyEvictions(Shard s, PutPlan plan) {
    for (Map.Entry<String, BlockEntry> ev : plan.toEvict()) {
      s.entries.remove(ev.getKey());
      s.currentBytes.addAndGet(-ev.getValue().length());
      s.freeSlots.addLast(ev.getValue().slot());
      if (evictionCounter != null) {
        evictionCounter.increment();
      }
    }
  }

  /**
   * Persistent action. Pop a slot from the free pool, copy the payload
   * into it. On thrown copy, return the slot to the free pool before
   * propagating the exception so {@code s.freeSlots} stays honest.
   */
  private void writeBytesToSlot(Shard s, PutPlan plan, String key, byte[] bytes) {
    long slot = s.freeSlots.pollFirst();
    plan.bind(slot);
    try {
      MemorySegment.copy(
          MemorySegment.ofArray(bytes), 0L,
          segmentFor(slot), offsetInSegment(slot),
          bytes.length);
    } catch (RuntimeException e) {
      // Orphan recovery: the slot is out of freeSlots and not yet in
      // entries. Return it before rethrowing so freeSlots stays
      // consistent with the LRU.
      s.freeSlots.addFirst(slot);
      if (LOG.isErrorEnabled()) {
        LOG.error(
            "L2 off-heap cache \"{}\" copy failed: key={} slot={} bytes={}",
            cacheName,
            key,
            slot,
            bytes.length,
            e);
      }
      throw e;
    }
  }

  /**
   * Bookkeeping that runs only after the persistent copy succeeds:
   * insert the new entry into the LRU; for the overwrite case, remove
   * the prior entry and return its slot to the free pool.
   */
  private void applyNewEntry(Shard s, PutPlan plan, String key, int putLen) {
    if (plan.existing() != null) {
      s.entries.remove(key);
      s.currentBytes.addAndGet(-plan.existingLen());
      s.freeSlots.addLast(plan.existingSlot());
    }
    s.entries.put(key, new BlockEntry(plan.boundSlot(), putLen));
    s.currentBytes.addAndGet(putLen);
  }

  @Override
  public void invalidate(String key) {
    ensureOpen();
    Shard s = shards[shard(key)];
    s.lock.lock();
    try {
      BlockEntry removed = s.entries.remove(key);
      if (removed != null) {
        s.currentBytes.addAndGet(-removed.length());
        s.freeSlots.addLast(removed.slot());
      }
    } finally {
      s.lock.unlock();
    }
  }

  @Override
  public void invalidateMatching(Predicate<String> keyPredicate) {
    ensureOpen();
    for (Shard s : shards) {
      s.lock.lock();
    }
    try {
      Map<Shard, List<String>> matchesByShard = collectMatches(keyPredicate);
      applyInvalidateBookkeeping(matchesByShard);
    } finally {
      for (int i = shards.length - 1; i >= 0; i--) {
        shards[i].lock.unlock();
      }
    }
  }

  private Map<Shard, List<String>> collectMatches(Predicate<String> keyPredicate) {
    Map<Shard, List<String>> matchesByShard = new IdentityHashMap<>();
    for (Shard s : shards) {
      List<String> shardMatches = new ArrayList<>();
      for (Map.Entry<String, BlockEntry> e : s.entries.entrySet()) {
        if (keyPredicate.test(e.getKey())) {
          shardMatches.add(e.getKey());
        }
      }
      matchesByShard.put(s, shardMatches);
    }
    return matchesByShard;
  }

  /**
   * Apply removals to each shard. No counter ticks (explicit removal,
   * not capacity-driven eviction). Slab bytes stay where they are; the
   * slot returns to the free pool and a future put will overwrite.
   */
  private static void applyInvalidateBookkeeping(Map<Shard, List<String>> matchesByShard) {
    for (Map.Entry<Shard, List<String>> e : matchesByShard.entrySet()) {
      Shard s = e.getKey();
      for (String key : e.getValue()) {
        BlockEntry removed = s.entries.remove(key);
        if (removed != null) {
          s.currentBytes.addAndGet(-removed.length());
          s.freeSlots.addLast(removed.slot());
        }
      }
    }
  }

  @Override
  public void invalidateAll() {
    ensureOpen();
    for (Shard s : shards) {
      s.lock.lock();
    }
    try {
      long slotsPerShard = totalSlots / SHARDS;
      for (int i = 0; i < SHARDS; i++) {
        Shard s = shards[i];
        s.entries.clear();
        s.freeSlots.clear();
        s.currentBytes.set(0L);
        long lo = i * slotsPerShard;
        long hi = (i == SHARDS - 1) ? totalSlots : (i + 1) * slotsPerShard;
        for (long slot = lo; slot < hi; slot++) {
          s.freeSlots.addLast(slot);
        }
      }
    } finally {
      for (int i = shards.length - 1; i >= 0; i--) {
        shards[i].lock.unlock();
      }
    }
  }

  @Override
  public CacheTierStats stats() {
    long bytes = 0;
    long entries = 0;
    for (Shard s : shards) {
      bytes += s.currentBytes.get();
      entries += s.entries.size();
    }
    return new CacheTierStats(
        hitCounter == null ? 0 : (long) hitCounter.count(),
        missCounter == null ? 0 : (long) missCounter.count(),
        evictionCounter == null ? 0 : (long) evictionCounter.count(),
        bytes,
        maxBytes,
        entries);
  }

  @Override
  public String tierName() {
    return TIER_L2_OFFHEAP;
  }

  @Override
  public void close() {
    for (Shard s : shards) {
      s.lock.lock();
    }
    try {
      if (closed) {
        return;
      }
      closed = true;
      // Unregister meters before releasing native resources: gauges hold
      // a strongReference to this instance via the Supplier<Number>, so
      // letting them outlive close() leaks the closed provider.
      if (meterRegistry != null) {
        for (Meter m : registeredMeters) {
          meterRegistry.remove(m);
        }
        registeredMeters.clear();
      }
      arena.close();
      for (Shard s : shards) {
        s.entries.clear();
        s.freeSlots.clear();
        s.currentBytes.set(0L);
      }
    } finally {
      for (int i = shards.length - 1; i >= 0; i--) {
        shards[i].lock.unlock();
      }
    }
  }

  // ---- helpers --------------------------------------------------------

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("L2 off-heap cache \"" + cacheName + "\" is closed");
    }
  }

  private static int shard(String key) {
    return Math.floorMod(key.hashCode(), SHARDS);
  }

  private MemorySegment segmentFor(long slot) {
    return segments[(int) (slot / slotsPerSegment)];
  }

  private long offsetInSegment(long slot) {
    return (slot % slotsPerSegment) * blockSize;
  }

  private Counter newCounter(
      MeterRegistry registry, String name, String description, Tags tags) {
    if (registry == null) {
      return null;
    }
    Counter c = Counter.builder(name).description(description).tags(tags).register(registry);
    registeredMeters.add(c);
    return c;
  }

  private void registerGauges(MeterRegistry registry, Tags tags) {
    if (registry == null) {
      return;
    }
    Gauge bytesGauge =
        Gauge.builder(HeapCacheTier.METER_BYTES, this, p -> (double) p.statsBytes())
            .description("Bytes currently held by the cache tier")
            .tags(tags)
            .strongReference(true)
            .register(registry);
    Gauge entriesGauge =
        Gauge.builder(HeapCacheTier.METER_ENTRIES, this, p -> (double) p.statsEntries())
            .description("Entry count currently held by the cache tier")
            .tags(tags)
            .strongReference(true)
            .register(registry);
    registeredMeters.add(bytesGauge);
    registeredMeters.add(entriesGauge);
  }

  private long statsBytes() {
    long total = 0;
    for (Shard s : shards) {
      total += s.currentBytes.get();
    }
    return total;
  }

  private long statsEntries() {
    long total = 0;
    for (Shard s : shards) {
      total += s.entries.size();
    }
    return total;
  }

  /**
   * One cached value: which slot it lives in and its actual payload
   * length. The slot occupies a full {@code blockSize} regardless;
   * {@code length} can be smaller for trailing-block payloads, so
   * reads return the recorded length rather than {@code blockSize}.
   */
  private record BlockEntry(long slot, int length) {}

  /**
   * Per-shard in-memory bookkeeping. {@code entries} is access-ordered
   * so iterating from the head gives oldest-first eviction candidates;
   * mutations are guarded by {@code lock}. {@code freeSlots} is the
   * shard's pool of unallocated slot indices. {@code currentBytes} is
   * an {@link AtomicLong} so {@link #stats()} and Prometheus gauges can
   * read the byte total without taking the shard lock.
   */
  private static final class Shard {
    final ReentrantLock lock = new ReentrantLock();
    final LinkedHashMap<String, BlockEntry> entries =
        new LinkedHashMap<>(16, 0.75f, /* accessOrder = */ true);
    final ArrayDeque<Long> freeSlots = new ArrayDeque<>();
    final AtomicLong currentBytes = new AtomicLong();
  }

  /**
   * Eviction plan computed in {@link #planPut} and bound to a destination
   * slot in {@link #writeBytesToSlot}. Mutable because the plan is local
   * to a single {@code put} under the shard lock; no aliasing.
   */
  private static final class PutPlan {
    private final BlockEntry existing;
    private final long existingLen;
    private final long existingSlot;
    private final List<Map.Entry<String, BlockEntry>> toEvict;
    private final long projected;
    private long boundSlot;

    PutPlan(
        BlockEntry existing,
        long existingLen,
        long existingSlot,
        List<Map.Entry<String, BlockEntry>> toEvict,
        long projected) {
      this.existing = existing;
      this.existingLen = existingLen;
      this.existingSlot = existingSlot;
      this.toEvict = toEvict;
      this.projected = projected;
    }

    BlockEntry existing() {
      return existing;
    }

    long existingLen() {
      return existingLen;
    }

    long existingSlot() {
      return existingSlot;
    }

    List<Map.Entry<String, BlockEntry>> toEvict() {
      return toEvict;
    }

    long projected() {
      return projected;
    }

    long boundSlot() {
      return boundSlot;
    }

    void bind(long slot) {
      this.boundSlot = slot;
    }
  }
}
