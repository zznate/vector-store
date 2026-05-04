package io.github.zznate.vectorstore.core.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.lmdbjava.CursorIterable;
import org.lmdbjava.CursorIterable.KeyVal;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-disk {@link L2Provider} backed by LMDB Java. LMDB owns durability,
 * crash safety, and disk integrity; the application owns byte-budget
 * eviction via an in-memory LRU index sharded across {@value #SHARDS}
 * shards. {@code mapsize} is the hard ceiling — pre-emptive eviction
 * keeps the live working set below {@value #SOFT_CAP_FRACTION_PERCENT}%
 * of each shard's mapsize budget so {@code MapFullException} should
 * never escape to the caller.
 *
 * <p><b>Threading:</b> LMDB internally serialises writer transactions
 * (calling {@code env.txnWrite()} from multiple threads simply blocks
 * the second one until the first commits) and offers wait-free MVCC
 * reads. The application adds per-shard {@link ReentrantLock}s only to
 * keep the in-memory LRU bookkeeping consistent; the LMDB read inside
 * {@link #get(String)} runs under that lock so a hit's payload always
 * matches the LRU's view of "this key is here".
 *
 * <p><b>Bookkeeping ordering:</b> all in-memory mutations (LRU map,
 * {@code currentBytes}, eviction counter) happen <i>after</i>
 * {@code txn.commit()} succeeds. If a write txn throws — most
 * realistically {@link Env.MapFullException} — LMDB rolls the txn back
 * and the in-memory state stays untouched, so disk and LRU never
 * diverge.
 *
 * <p><b>Warm restart:</b> on construction, an iteration over the LMDB
 * environment populates the per-shard LRU index. The LRU access order
 * resets to the cursor's key-byte order; the key set is preserved.
 */
// Cyclomatic-complexity suppression: 8-shard striping + drift healing +
// nullable-counter guards + per-tier MeterRegistry plumbing push the
// summed class metric above PMD's 80 threshold even though every
// individual method is small. Splitting across helper classes would
// fragment the provider without improving readability.
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class LmdbL2Provider implements L2Provider {

  private static final Logger LOG = LoggerFactory.getLogger(LmdbL2Provider.class);

  public static final String TIER_L2_DISK = "l2_disk";
  public static final String DEFAULT_CACHE_NAME = "block";

  private static final int SHARDS = 8;
  private static final double SOFT_CAP_FRACTION = 0.75;
  private static final int SOFT_CAP_FRACTION_PERCENT = 75;

  private static final String DBI_NAME = "blocks";
  private static final int MAX_READERS = 64;

  private static final String LEGACY_DATA_FILE = "data.bin";
  private static final String LEGACY_INDEX_FILE = "index.bin";

  private final Path directory;
  private final long maxBytes;
  private final long perShardSoftCap;
  private final String cacheName;
  private final Env<ByteBuffer> env;
  private final Dbi<ByteBuffer> dbi;
  private final Shard[] shards;

  private final Counter hitCounter;
  private final Counter missCounter;
  private final Counter evictionCounter;

  private volatile boolean closed;

  public LmdbL2Provider(Path directory, long maxBytes, MeterRegistry meterRegistry) {
    this(directory, maxBytes, meterRegistry, DEFAULT_CACHE_NAME);
  }

  public LmdbL2Provider(
      Path directory, long maxBytes, MeterRegistry meterRegistry, String cacheName) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
    }
    this.directory = directory;
    this.maxBytes = maxBytes;
    this.perShardSoftCap = (long) ((double) (maxBytes / SHARDS) * SOFT_CAP_FRACTION);
    this.cacheName = cacheName;

    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create L2 disk cache directory " + directory, e);
    }

    this.env =
        Env.create()
            .setMapSize(maxBytes)
            .setMaxDbs(1)
            .setMaxReaders(MAX_READERS)
            .open(directory.toFile());
    this.dbi = env.openDbi(DBI_NAME, DbiFlags.MDB_CREATE);

    Shard[] s = new Shard[SHARDS];
    for (int i = 0; i < SHARDS; i++) {
      s[i] = new Shard();
    }
    this.shards = s;

    warmRestartScan();
    warnIfLegacyFilesPresent();

    Tags tags =
        Tags.of(HeapCacheTier.TIER_TAG, TIER_L2_DISK, HeapCacheTier.CACHE_NAME_TAG, cacheName);
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
      Long len = s.lru.get(key);
      if (len == null) {
        if (missCounter != null) {
          missCounter.increment();
        }
        return Optional.empty();
      }
      try (Txn<ByteBuffer> txn = env.txnRead()) {
        ByteBuffer val = dbi.get(txn, utf8Key(key));
        if (val == null) {
          if (LOG.isWarnEnabled()) {
            LOG.warn(
                "L2 disk cache \"{}\": LRU/LMDB drift on key {} — LRU has it, LMDB doesn't; healing",
                cacheName,
                key);
          }
          s.lru.remove(key);
          s.currentBytes.addAndGet(-len);
          if (missCounter != null) {
            missCounter.increment();
          }
          return Optional.empty();
        }
        byte[] copy = new byte[val.remaining()];
        val.duplicate().get(copy);
        if (hitCounter != null) {
          hitCounter.increment();
        }
        return Optional.of(copy);
      }
    } finally {
      s.lock.unlock();
    }
  }

  @Override
  public void put(String key, byte[] bytes) {
    ensureOpen();
    if (bytes.length > maxBytes) {
      logOversizedRejection(key, bytes.length);
      return;
    }
    Shard s = shards[shard(key)];
    s.lock.lock();
    try {
      PutPlan plan = planPut(s, key, bytes.length);
      commitPut(plan, key, bytes);
      applyPutBookkeeping(s, plan, key, bytes.length);
    } finally {
      s.lock.unlock();
    }
  }

  private void logOversizedRejection(String key, int length) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "L2 disk cache \"{}\" rejecting oversized put: key={} bytes={} maxBytes={}",
          cacheName,
          key,
          length,
          maxBytes);
    }
  }

  /**
   * Pre-commit planning: peek at LRU to determine which existing entry
   * (if any) is being overwritten and which oldest entries must evict to
   * keep the post-put state under the per-shard soft cap. <i>No</i>
   * mutation of LRU / currentBytes happens here.
   */
  private PutPlan planPut(Shard s, String key, int putLen) {
    Long existingLen = s.lru.get(key);
    long baseline = s.currentBytes.get() - (existingLen == null ? 0 : existingLen);
    long projected = baseline + putLen;
    List<Map.Entry<String, Long>> toEvict = new ArrayList<>();
    Iterator<Map.Entry<String, Long>> it = s.lru.entrySet().iterator();
    while (projected > perShardSoftCap && it.hasNext()) {
      Map.Entry<String, Long> oldest = it.next();
      if (oldest.getKey().equals(key)) {
        continue;
      }
      toEvict.add(Map.entry(oldest.getKey(), oldest.getValue()));
      projected -= oldest.getValue();
    }
    return new PutPlan(existingLen, toEvict, projected);
  }

  /**
   * Stage all LMDB mutations for a {@code put} in a single write txn. If
   * commit throws {@link Env.MapFullException}, log at ERROR (the soft
   * cap is the contract; reaching this means the cap needs tuning) and
   * rethrow without touching in-memory state — Trap 7.
   */
  private void commitPut(PutPlan plan, String key, byte[] bytes) {
    try (Txn<ByteBuffer> txn = env.txnWrite()) {
      for (Map.Entry<String, Long> evicted : plan.toEvict()) {
        dbi.delete(txn, utf8Key(evicted.getKey()));
      }
      dbi.put(txn, utf8Key(key), valueBuf(bytes));
      txn.commit();
    } catch (Env.MapFullException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(
            "L2 disk cache \"{}\": MapFullException despite pre-eviction; "
                + "soft cap may need tuning. shard={} projected={} cap={}",
            cacheName,
            shard(key),
            plan.projected(),
            perShardSoftCap,
            e);
      }
      throw e;
    }
  }

  /**
   * Apply the in-memory consequences of a successful {@code put}: evict
   * planned keys from the LRU, decrement {@code currentBytes} for the
   * overwritten entry (if any), insert the new key. Eviction counter
   * ticks here, after the LMDB commit succeeded.
   */
  private void applyPutBookkeeping(Shard s, PutPlan plan, String key, int putLen) {
    for (Map.Entry<String, Long> evicted : plan.toEvict()) {
      Long evLen = s.lru.remove(evicted.getKey());
      if (evLen != null) {
        s.currentBytes.addAndGet(-evLen);
        if (evictionCounter != null) {
          evictionCounter.increment();
        }
      }
    }
    if (plan.existingLen() != null) {
      s.currentBytes.addAndGet(-plan.existingLen());
    }
    s.lru.put(key, (long) putLen);
    s.currentBytes.addAndGet(putLen);
  }

  /**
   * Captures a pre-commit eviction plan: which entry (if any) is being
   * overwritten, which oldest entries must evict, and the projected
   * bytes total after the planned mutation. {@code projected} is kept
   * for the {@link Env.MapFullException} ERROR log.
   */
  private record PutPlan(
      Long existingLen, List<Map.Entry<String, Long>> toEvict, long projected) {}

  @Override
  public void invalidate(String key) {
    ensureOpen();
    Shard s = shards[shard(key)];
    s.lock.lock();
    try {
      if (!s.lru.containsKey(key)) {
        return;
      }
      try (Txn<ByteBuffer> txn = env.txnWrite()) {
        dbi.delete(txn, utf8Key(key));
        txn.commit();
      }
      Long len = s.lru.remove(key);
      if (len != null) {
        s.currentBytes.addAndGet(-len);
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
      List<String> allMatches = flatten(matchesByShard);
      commitDeletes(allMatches);
      applyInvalidateBookkeeping(matchesByShard);
    } finally {
      for (int i = shards.length - 1; i >= 0; i--) {
        shards[i].lock.unlock();
      }
    }
  }

  /**
   * Step 1 of {@code invalidateMatching}: scan every shard's LRU under
   * the all-shards lock and collect matching keys without mutating
   * anything yet (in-memory bookkeeping must follow {@code txn.commit()}).
   */
  private Map<Shard, List<String>> collectMatches(Predicate<String> keyPredicate) {
    Map<Shard, List<String>> matchesByShard = new IdentityHashMap<>();
    for (Shard s : shards) {
      List<String> shardMatches = new ArrayList<>();
      for (Map.Entry<String, Long> e : s.lru.entrySet()) {
        if (keyPredicate.test(e.getKey())) {
          shardMatches.add(e.getKey());
        }
      }
      matchesByShard.put(s, shardMatches);
    }
    return matchesByShard;
  }

  private static List<String> flatten(Map<Shard, List<String>> matchesByShard) {
    List<String> all = new ArrayList<>();
    for (List<String> shardMatches : matchesByShard.values()) {
      all.addAll(shardMatches);
    }
    return all;
  }

  /** Step 2 of {@code invalidateMatching}: stage the LMDB deletes and commit. */
  private void commitDeletes(List<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    try (Txn<ByteBuffer> txn = env.txnWrite()) {
      for (String key : keys) {
        dbi.delete(txn, utf8Key(key));
      }
      txn.commit();
    }
  }

  /**
   * Step 3 of {@code invalidateMatching}: commit succeeded; apply the
   * in-memory removals shard by shard. No counter ticks (explicit
   * removal, not capacity-driven eviction).
   */
  private static void applyInvalidateBookkeeping(Map<Shard, List<String>> matchesByShard) {
    for (Map.Entry<Shard, List<String>> e : matchesByShard.entrySet()) {
      Shard s = e.getKey();
      for (String key : e.getValue()) {
        Long len = s.lru.remove(key);
        if (len != null) {
          s.currentBytes.addAndGet(-len);
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
      try (Txn<ByteBuffer> txn = env.txnWrite()) {
        dbi.drop(txn, /* delete = */ false);
        txn.commit();
      }
      for (Shard s : shards) {
        s.lru.clear();
        s.currentBytes.set(0L);
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
      entries += s.lru.size();
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
    return TIER_L2_DISK;
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
      // env.close() returns immediately even with open read txns; the
      // orphans throw AlreadyClosedException on next op.
      env.close();
      for (Shard s : shards) {
        s.lru.clear();
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
      throw new IllegalStateException("L2 disk cache \"" + cacheName + "\" is closed");
    }
  }

  private static int shard(String key) {
    return Math.floorMod(key.hashCode(), SHARDS);
  }

  private static ByteBuffer utf8Key(String key) {
    byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length);
    buf.put(bytes).flip();
    return buf;
  }

  private static ByteBuffer valueBuf(byte[] bytes) {
    ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length);
    buf.put(bytes).flip();
    return buf;
  }

  /**
   * Iterate the LMDB env once to repopulate the per-shard LRU + byte
   * counters from the on-disk key set. Reads keys + value lengths only;
   * the value bytes stay page-cache-resident for later reads. Resets
   * LRU access order (cursor walks key-byte order, not historical
   * access order — documented in the class Javadoc).
   */
  private void warmRestartScan() {
    try (Txn<ByteBuffer> txn = env.txnRead();
        CursorIterable<ByteBuffer> iter = dbi.iterate(txn)) {
      for (KeyVal<ByteBuffer> kv : iter) {
        ByteBuffer kb = kv.key();
        byte[] kbytes = new byte[kb.remaining()];
        kb.duplicate().get(kbytes);
        String key = new String(kbytes, StandardCharsets.UTF_8);
        long len = kv.val().remaining();
        Shard s = shards[shard(key)];
        s.lru.put(key, len);
        s.currentBytes.addAndGet(len);
      }
    }
  }

  private void warnIfLegacyFilesPresent() {
    Path legacyData = directory.resolve(LEGACY_DATA_FILE);
    Path legacyIndex = directory.resolve(LEGACY_INDEX_FILE);
    if ((Files.exists(legacyData) || Files.exists(legacyIndex)) && LOG.isWarnEnabled()) {
      LOG.warn(
          "L2 disk cache \"{}\" directory {} contains legacy files ({}/{}) "
              + "from a previous disk-tier implementation. They are orphaned and "
              + "safe to delete; LMDB ignores them.",
          cacheName,
          directory,
          LEGACY_DATA_FILE,
          LEGACY_INDEX_FILE);
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
    Gauge.builder(HeapCacheTier.METER_BYTES, this, p -> (double) p.statsBytes())
        .description("Bytes currently held by the cache tier")
        .tags(tags)
        .strongReference(true)
        .register(registry);
    Gauge.builder(HeapCacheTier.METER_ENTRIES, this, p -> (double) p.statsEntries())
        .description("Entry count currently held by the cache tier")
        .tags(tags)
        .strongReference(true)
        .register(registry);
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
      total += s.lru.size();
    }
    return total;
  }

  /**
   * Per-shard in-memory bookkeeping. The {@code lru} map is access-order
   * so iterating from the head gives oldest-first eviction candidates;
   * mutations are guarded by {@code lock}. {@code currentBytes} is an
   * {@link AtomicLong} so {@link #stats()} and Prometheus gauges can read
   * the byte total without taking the shard lock.
   */
  private static final class Shard {
    final ReentrantLock lock = new ReentrantLock();
    final LinkedHashMap<String, Long> lru =
        new LinkedHashMap<>(16, 0.75f, /* accessOrder = */ true);
    final AtomicLong currentBytes = new AtomicLong();
  }
}
