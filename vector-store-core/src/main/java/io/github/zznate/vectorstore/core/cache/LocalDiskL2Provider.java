package io.github.zznate.vectorstore.core.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-disk {@link L2Provider} backed by a single memory-mapped data file
 * with a bump-pointer allocator and an in-memory keyed index. Targets
 * NVMe-class deployments where the off-heap arena tier is too small and
 * S3 round-trips are still too slow.
 *
 * <p>Layout: the data file is pre-allocated to {@link #maxBytes} bytes
 * and held mapped via {@link FileChannel#map(FileChannel.MapMode, long,
 * long, Arena)} for the lifetime of the provider. The mapping is owned
 * by an {@link Arena} so {@link #close()} can unmap deterministically
 * instead of relying on GC, and the JDK 21 FFM API supports {@code
 * long}-indexed mappings — no 2 GiB cap. Entries are written
 * sequentially via the bump pointer, wrapping around to {@code 0} when
 * the next write would exceed the file size. On wrap, any existing
 * entry whose byte range overlaps the new write is evicted (eviction
 * proceeds from the oldest write position, approximating LRU under
 * typical access patterns).
 *
 * <p>Free-list reclaim: invalidating an entry returns its slot to a
 * size-bucketed free list. The next {@code put} of identical payload
 * size reuses that slot before bump-allocating.
 *
 * <p>Persistence: on clean {@link #close()}, the index is serialised to
 * an {@code index.bin} sidecar via {@link LocalDiskCacheIndex}. On
 * startup, if the sidecar parses cleanly we warm-restart against the
 * cached data; if anything is wrong we fall back to a cold start
 * without loss-of-data — the data file stays in place and a fresh
 * index begins.
 *
 * <p>Concurrency: every mutation and read is serialised by a single
 * {@link ReentrantLock}, identical to {@link OffHeapArenaL2Provider}.
 * A {@link FileLock} on the data file enforces single-process
 * exclusivity; the constructor throws if another holder owns the lock.
 */
public final class LocalDiskL2Provider implements L2Provider {

  private static final Logger LOG = LoggerFactory.getLogger(LocalDiskL2Provider.class);

  public static final String TIER_L2_DISK = "l2_disk";
  public static final String DEFAULT_CACHE_NAME = "block";

  static final String DATA_FILE_NAME = "data.bin";
  static final String INDEX_FILE_NAME = "index.bin";

  private final Path dataFile;
  private final Path indexFile;
  private final FileChannel fileChannel;
  private final FileLock fileLock;
  private final Arena arena;
  private final MemorySegment segment;
  private final long maxBytes;
  private final String cacheName;

  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, DiskEntry> entries =
      new LinkedHashMap<>(16, 0.75f, /* accessOrder */ true);
  private final Map<Integer, ArrayDeque<Long>> freeList = new HashMap<>();

  private long allocOffset;
  private long currentBytes;

  private final Counter hitCounter;
  private final Counter missCounter;
  private final Counter evictionCounter;

  private boolean closed;

  public LocalDiskL2Provider(Path directory, long maxBytes, MeterRegistry meterRegistry) {
    this(directory, maxBytes, meterRegistry, DEFAULT_CACHE_NAME);
  }

  public LocalDiskL2Provider(
      Path directory, long maxBytes, MeterRegistry meterRegistry, String cacheName) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
    }
    this.maxBytes = maxBytes;
    this.cacheName = cacheName;
    this.dataFile = directory.resolve(DATA_FILE_NAME);
    this.indexFile = directory.resolve(INDEX_FILE_NAME);

    try {
      Files.createDirectories(directory);
      this.fileChannel =
          FileChannel.open(
              dataFile,
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE);
      this.fileLock = acquireExclusiveLock(fileChannel, dataFile);
      ensureFileSize(fileChannel, maxBytes);
      // Arena owns the mapping; close() unmaps deterministically.
      // ofShared() so reads / writes from any thread are legal under
      // the existing single-lock serialisation.
      this.arena = Arena.ofShared();
      this.segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0L, maxBytes, arena);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to open L2 disk cache at " + directory, e);
    }

    Tags tags =
        Tags.of(HeapCacheTier.TIER_TAG, TIER_L2_DISK, HeapCacheTier.CACHE_NAME_TAG, cacheName);
    this.hitCounter =
        newCounter(meterRegistry, HeapCacheTier.METER_HIT, "Cache hits tagged by tier and cache name", tags);
    this.missCounter =
        newCounter(meterRegistry, HeapCacheTier.METER_MISS, "Cache misses tagged by tier and cache name", tags);
    this.evictionCounter =
        newCounter(meterRegistry, HeapCacheTier.METER_EVICTION, "Cache evictions tagged by tier and cache name", tags);
    registerGauges(meterRegistry, tags);

    LocalDiskCacheIndex.tryLoad(indexFile, maxBytes, cacheName).ifPresent(this::adoptReloadedIndex);
  }

  @Override
  public Optional<byte[]> get(String key) {
    lock.lock();
    try {
      DiskEntry entry = entries.get(key);
      if (entry == null) {
        if (missCounter != null) {
          missCounter.increment();
        }
        return Optional.empty();
      }
      byte[] copy = readPayload(entry);
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
            "L2 disk cache \"{}\" rejecting oversized put: key={} bytes={} maxBytes={}",
            cacheName,
            key,
            bytes.length,
            maxBytes);
      }
      return;
    }
    lock.lock();
    try {
      removeExisting(key);
      long offset = reserveSlot(bytes.length);
      writePayload(offset, bytes);
      entries.put(key, new DiskEntry(offset, bytes.length));
      currentBytes += bytes.length;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidate(String key) {
    lock.lock();
    try {
      removeExisting(key);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidateMatching(Predicate<String> keyPredicate) {
    lock.lock();
    try {
      Iterator<Map.Entry<String, DiskEntry>> it = entries.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, DiskEntry> mapEntry = it.next();
        if (keyPredicate.test(mapEntry.getKey())) {
          it.remove();
          DiskEntry diskEntry = mapEntry.getValue();
          currentBytes -= diskEntry.length();
          releaseToFreeList(diskEntry.offset(), diskEntry.length());
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
      entries.clear();
      freeList.clear();
      currentBytes = 0;
      allocOffset = 0;
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
          currentBytes,
          maxBytes,
          (long) entries.size());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String tierName() {
    return TIER_L2_DISK;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      // Force dirty pages to disk BEFORE persisting the index sidecar
      // so the index can never reference bytes that survived only in
      // the page cache; if we crash post-index-persist before the OS
      // flushes, warm restart would otherwise read garbage.
      segment.force();
      LocalDiskCacheIndex.persist(indexFile, entries, allocOffset, currentBytes, cacheName);
      // Closing the Arena unmaps the segment deterministically — no
      // reliance on GC for native-resource cleanup.
      arena.close();
      releaseLock();
      closeChannel();
      closed = true;
    } finally {
      lock.unlock();
    }
  }

  private void releaseLock() {
    try {
      if (fileLock != null && fileLock.isValid()) {
        fileLock.release();
      }
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("failed to release file lock on {}", dataFile, e);
      }
    }
  }

  private void closeChannel() {
    try {
      fileChannel.close();
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("failed to close file channel for {}", dataFile, e);
      }
    }
  }

  // ---- Slot allocation ------------------------------------------------

  /**
   * Pick an offset to write {@code length} bytes into. Tries the free
   * list first (size-matched reclaim), then bump-allocates, evicting any
   * entries that the new write would clobber on wrap.
   */
  private long reserveSlot(int length) {
    Long reused = takeFromFreeList(length);
    if (reused != null) {
      return reused;
    }
    if (allocOffset + length > maxBytes) {
      allocOffset = 0;
    }
    long writeStart = allocOffset;
    long writeEnd = writeStart + length;
    evictOverlapping(writeStart, writeEnd);
    allocOffset = writeEnd;
    return writeStart;
  }

  private Long takeFromFreeList(int length) {
    ArrayDeque<Long> bucket = freeList.get(length);
    if (bucket == null || bucket.isEmpty()) {
      return null;
    }
    Long offset = bucket.pollFirst();
    if (bucket.isEmpty()) {
      freeList.remove(length);
    }
    return offset;
  }

  private void releaseToFreeList(long offset, int length) {
    freeList.computeIfAbsent(length, k -> new ArrayDeque<>()).addLast(offset);
  }

  /**
   * Evict every entry whose stored byte range overlaps {@code [start, end)}.
   * Called when the bump pointer wraps over previously-written data; any
   * entry in the about-to-be-overwritten region must be removed from the
   * index (its bytes are about to disappear).
   */
  private void evictOverlapping(long start, long end) {
    Iterator<Map.Entry<String, DiskEntry>> it = entries.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, DiskEntry> mapEntry = it.next();
      DiskEntry diskEntry = mapEntry.getValue();
      long entryEnd = diskEntry.offset() + diskEntry.length();
      if (diskEntry.offset() < end && entryEnd > start) {
        it.remove();
        currentBytes -= diskEntry.length();
        // Slot is being overwritten, don't return it to the free list —
        // the new payload is taking it.
        if (evictionCounter != null) {
          evictionCounter.increment();
        }
      }
    }
  }

  private void removeExisting(String key) {
    DiskEntry existing = entries.remove(key);
    if (existing != null) {
      currentBytes -= existing.length();
      releaseToFreeList(existing.offset(), existing.length());
    }
  }

  // ---- Mmap I/O -------------------------------------------------------

  private byte[] readPayload(DiskEntry entry) {
    byte[] buf = new byte[entry.length()];
    MemorySegment.copy(segment, entry.offset(), MemorySegment.ofArray(buf), 0L, entry.length());
    return buf;
  }

  private void writePayload(long offset, byte[] bytes) {
    MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, segment, offset, bytes.length);
  }

  // ---- Misc helpers ---------------------------------------------------

  /** Adopt the state returned by a successful index-sidecar reload. */
  private void adoptReloadedIndex(LocalDiskCacheIndex.LoadedState state) {
    entries.putAll(state.entries());
    allocOffset = state.allocOffset();
    currentBytes = state.currentBytes();
  }

  long currentBytesSnapshot() {
    lock.lock();
    try {
      return currentBytes;
    } finally {
      lock.unlock();
    }
  }

  int entryCountSnapshot() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  private static Counter newCounter(
      MeterRegistry registry, String name, String description, Tags tags) {
    if (registry == null) {
      return null;
    }
    return Counter.builder(name).description(description).tags(tags).register(registry);
  }

  private void registerGauges(MeterRegistry registry, Tags tags) {
    if (registry == null) {
      return;
    }
    Gauge.builder(HeapCacheTier.METER_BYTES, this, p -> (double) p.currentBytesSnapshot())
        .description("Bytes currently held by the cache tier")
        .tags(tags)
        .strongReference(true)
        .register(registry);
    Gauge.builder(HeapCacheTier.METER_ENTRIES, this, p -> (double) p.entryCountSnapshot())
        .description("Entry count currently held by the cache tier")
        .tags(tags)
        .strongReference(true)
        .register(registry);
  }

  /**
   * Acquire an exclusive lock on the data file. Same-JVM contention
   * surfaces as {@link OverlappingFileLockException}; everything else
   * (cross-process contention, channel closed mid-attempt) shows up as
   * {@code tryLock} returning null. Both reach the caller as a plain
   * {@link IOException} so the constructor only has one shape to handle.
   */
  private static FileLock acquireExclusiveLock(FileChannel channel, Path dataFile)
      throws IOException {
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      throw new IOException(
          "Another holder owns the L2 disk cache lock at " + dataFile, e);
    }
    if (lock == null) {
      throw new IOException(
          "Could not acquire L2 disk cache lock at " + dataFile);
    }
    return lock;
  }

  private static void ensureFileSize(FileChannel channel, long maxBytes) throws IOException {
    if (channel.size() < maxBytes) {
      // Pre-allocate by writing a single byte at the last position. On
      // most filesystems this is sparse; on those that aren't, the OS
      // performs the allocation up-front so subsequent mmap'd writes
      // never block on file extension.
      ByteBuffer one = ByteBuffer.allocate(1);
      channel.write(one, maxBytes - 1);
    } else if (channel.size() > maxBytes) {
      channel.truncate(maxBytes);
    }
  }

  /**
   * Single cached entry: where its bytes live in the data file and how
   * many bytes long the payload is. Package-private so the sidecar
   * helper can construct one when reloading.
   */
  record DiskEntry(long offset, int length) {}
}
