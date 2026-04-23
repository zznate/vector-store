package io.github.zznate.vectorstore.storage.reader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.storage.cache.BlockCache;
import io.github.zznate.vectorstore.storage.cache.BlockKey;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the block-caching decorator. Rather than mocking the S3
 * client, these tests install a hand-rolled {@link CountingUnderlying} that
 * impersonates {@link S3RandomAccessReader} closely enough to exercise
 * {@code readRange} behaviour deterministically.
 */
class BlockCachingRandomAccessReaderTest {

  private static final String OBJECT_KEY = "bucket/index/seg/graph.jvec";
  private static final int BLOCK_SIZE = 16;

  private MeterRegistry registry;
  private byte[] objectBytes;
  private CountingUnderlying underlying;
  private BlockCache cache;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    // 128 bytes = 8 blocks of 16 bytes each.
    objectBytes = new byte[128];
    for (int i = 0; i < objectBytes.length; i++) {
      objectBytes[i] = (byte) i;
    }
    underlying = new CountingUnderlying(objectBytes);
    cache = new BlockCache(1 << 20, registry);
  }

  @Test
  void coldReadMissesAllBlocksAndPopulatesCache() throws Exception {
    try (BlockCachingRandomAccessReader reader = newReader()) {
      byte[] out = new byte[48]; // spans 3 blocks
      reader.seek(0);
      reader.readFully(out);

      byte[] expected = new byte[48];
      System.arraycopy(objectBytes, 0, expected, 0, 48);
      assertThat(out).containsExactly(expected);
    }

    assertThat(underlying.rangeCalls()).isEqualTo(3);
    assertThat(cacheMisses()).isEqualTo(3.0);
    assertThat(cacheHits()).isZero();
    assertThat(cache.getIfPresent(new BlockKey(OBJECT_KEY, 0))).isNotNull();
    assertThat(cache.getIfPresent(new BlockKey(OBJECT_KEY, 1))).isNotNull();
    assertThat(cache.getIfPresent(new BlockKey(OBJECT_KEY, 2))).isNotNull();
  }

  @Test
  void warmReadHitsCacheOnSecondPass() throws Exception {
    try (BlockCachingRandomAccessReader cold = newReader()) {
      cold.seek(0);
      byte[] warmUp = new byte[48];
      cold.readFully(warmUp);
    }
    int coldRanges = underlying.rangeCalls();

    try (BlockCachingRandomAccessReader warm = newReader()) {
      warm.seek(0);
      byte[] out = new byte[48];
      warm.readFully(out);

      byte[] expected = new byte[48];
      System.arraycopy(objectBytes, 0, expected, 0, 48);
      assertThat(out).containsExactly(expected);
    }

    assertThat(underlying.rangeCalls()).isEqualTo(coldRanges); // no new GETs
    assertThat(cacheHits()).isEqualTo(3.0);
  }

  @Test
  void crossBlockReadAssemblesContiguousBytesFromTwoBlocks() throws Exception {
    try (BlockCachingRandomAccessReader reader = newReader()) {
      reader.seek(10); // starts inside block 0, ends inside block 1
      byte[] out = new byte[12];
      reader.readFully(out);

      byte[] expected = new byte[12];
      System.arraycopy(objectBytes, 10, expected, 0, 12);
      assertThat(out).containsExactly(expected);
    }

    assertThat(underlying.rangeCalls()).isEqualTo(2);
    assertThat(cacheMisses()).isEqualTo(2.0);
  }

  @Test
  void readIntRespectsBigEndianAcrossBlockBoundary() throws Exception {
    // Prepare a known int spanning bytes 14..17 (blocks 0 and 1).
    ByteBuffer.wrap(objectBytes).order(ByteOrder.BIG_ENDIAN).putInt(14, 0xDEADBEEF);

    try (BlockCachingRandomAccessReader reader = newReader()) {
      reader.seek(14);
      assertThat(reader.readInt()).isEqualTo(0xDEADBEEF);
    }
    assertThat(underlying.rangeCalls()).isEqualTo(2);
  }

  @Test
  void byteWeightedEvictionDropsOldBlocksWhenBudgetExceeded() throws Exception {
    // Budget = 2 blocks worth (32 bytes); fill 3 blocks -> eviction.
    BlockCache tinyCache = new BlockCache(BLOCK_SIZE * 2L, registry);
    try (BlockCachingRandomAccessReader reader =
        new BlockCachingRandomAccessReader(
            underlying, OBJECT_KEY, BLOCK_SIZE, objectBytes.length, tinyCache, registry)) {
      reader.seek(0);
      reader.readFully(new byte[48]); // blocks 0, 1, 2
    }

    // Caffeine eviction is asynchronous; pulse the cleanup.
    for (int i = 0; i < 10 && tinyCache.estimatedSize() > 2; i++) {
      Thread.sleep(20);
    }
    assertThat(tinyCache.estimatedSize()).isLessThanOrEqualTo(2);
  }

  @Test
  void lastBlockIsShortenedForNonAlignedObjectLength() throws Exception {
    byte[] smallObject = new byte[20]; // block 0 = 16B, block 1 = 4B
    for (int i = 0; i < smallObject.length; i++) {
      smallObject[i] = (byte) (i + 1);
    }
    CountingUnderlying shortUnderlying = new CountingUnderlying(smallObject);
    try (BlockCachingRandomAccessReader reader =
        new BlockCachingRandomAccessReader(
            shortUnderlying, OBJECT_KEY, BLOCK_SIZE, smallObject.length, cache, registry)) {
      reader.seek(16);
      byte[] out = new byte[4];
      reader.readFully(out);
      assertThat(out).containsExactly(smallObject[16], smallObject[17], smallObject[18], smallObject[19]);
    }
    assertThat(shortUnderlying.rangeCalls()).isEqualTo(1);
  }

  private BlockCachingRandomAccessReader newReader() {
    return new BlockCachingRandomAccessReader(
        underlying, OBJECT_KEY, BLOCK_SIZE, objectBytes.length, cache, registry);
  }

  private double cacheHits() {
    return registry
        .counter("vectorstore.cache.hit", "tier", "l1_heap", "cache_name", "block")
        .count();
  }

  private double cacheMisses() {
    return registry
        .counter("vectorstore.cache.miss", "tier", "l1_heap", "cache_name", "block")
        .count();
  }

  /**
   * In-memory {@link RangeReader} that counts every call so the tests can
   * assert how many cold-path fetches the decorator issued.
   */
  static final class CountingUnderlying implements RangeReader {
    private final byte[] payload;
    private final AtomicInteger rangeCalls = new AtomicInteger();

    CountingUnderlying(byte[] payload) {
      this.payload = payload;
    }

    @Override
    public void readRange(long startOffset, byte[] dst, int dstOffset, int length) {
      rangeCalls.incrementAndGet();
      System.arraycopy(payload, (int) startOffset, dst, dstOffset, length);
    }

    @Override
    public void close() {
      // no-op
    }

    int rangeCalls() {
      return rangeCalls.get();
    }
  }
}
