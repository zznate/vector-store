package io.github.zznate.vectorstore.storage.reader;

import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.zznate.vectorstore.storage.cache.BlockCache;
import io.github.zznate.vectorstore.storage.cache.BlockKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * {@link RandomAccessReader} decorator that satisfies reads out of a shared
 * {@link BlockCache}, falling back to a wrapped reader for cold blocks.
 *
 * <p>Every read is split into fixed-size logical blocks (key size:
 * {@code vectorstore.storage.block-cache.block-size}). Each block is either
 * already present in the cache ({@code vectorstore.cache.block.hit}) or
 * loaded through the underlying reader ({@code vectorstore.cache.block.miss}).
 * Hits record a {@code vectorstore.storage.get.duration} sample tagged
 * {@code cache_hit=true}; misses flow through the underlying reader, which
 * records its own {@code cache_hit=false} sample and the transferred-bytes
 * counter.
 *
 * <p>The decorator is per-thread stateful, mirroring JVector's reader
 * contract. Multiple decorator instances can concurrently probe the same
 * {@link BlockCache}; Caffeine handles the concurrent access.
 */
public final class BlockCachingRandomAccessReader implements RandomAccessReader {

  private static final String METER_GET_DURATION = "vectorstore.storage.get.duration";
  private static final String METER_CACHE_HIT = "vectorstore.cache.block.hit";
  private static final String METER_CACHE_MISS = "vectorstore.cache.block.miss";
  private static final String TAG_CACHE_HIT = "cache_hit";

  private final RangeReader underlying;
  private final String objectKey;
  private final int blockSize;
  private final long objectLength;
  private final BlockCache cache;
  private final MeterRegistry meterRegistry;

  private long position;
  private boolean closed;

  public BlockCachingRandomAccessReader(
      RangeReader underlying,
      String objectKey,
      int blockSize,
      long objectLength,
      BlockCache cache,
      MeterRegistry meterRegistry) {
    if (blockSize <= 0) {
      throw new IllegalArgumentException("blockSize must be > 0, got " + blockSize);
    }
    this.underlying = underlying;
    this.objectKey = objectKey;
    this.blockSize = blockSize;
    this.objectLength = objectLength;
    this.cache = cache;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void seek(long newPosition) {
    checkOpen();
    this.position = newPosition;
  }

  @Override
  public long getPosition() {
    checkOpen();
    return position;
  }

  @Override
  public long length() {
    return objectLength;
  }

  @Override
  public int readInt() throws IOException {
    byte[] buf = new byte[Integer.BYTES];
    readFully(buf);
    return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).getInt();
  }

  @Override
  public long readLong() throws IOException {
    byte[] buf = new byte[Long.BYTES];
    readFully(buf);
    return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).getLong();
  }

  @Override
  public float readFloat() throws IOException {
    return Float.intBitsToFloat(readInt());
  }

  @Override
  public void readFully(byte[] dst) throws IOException {
    fetchBytes(position, dst, 0, dst.length);
    position += dst.length;
  }

  @Override
  public void readFully(ByteBuffer dst) throws IOException {
    int remaining = dst.remaining();
    if (remaining == 0) {
      return;
    }
    byte[] buf = new byte[remaining];
    fetchBytes(position, buf, 0, remaining);
    dst.put(buf);
    position += remaining;
  }

  @Override
  public void readFully(long[] dst) throws IOException {
    byte[] buf = new byte[dst.length * Long.BYTES];
    readFully(buf);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < dst.length; i++) {
      dst[i] = bb.getLong();
    }
  }

  @Override
  public void read(int[] dst, int offset, int count) throws IOException {
    byte[] buf = new byte[count * Integer.BYTES];
    readFully(buf);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < count; i++) {
      dst[offset + i] = bb.getInt();
    }
  }

  @Override
  public void read(float[] dst, int offset, int count) throws IOException {
    byte[] buf = new byte[count * Float.BYTES];
    readFully(buf);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < count; i++) {
      dst[offset + i] = bb.getFloat();
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    underlying.close();
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("reader already closed (" + objectKey + ")");
    }
  }

  private void fetchBytes(long offset, byte[] dst, int dstOffset, int length) throws IOException {
    checkOpen();
    if (length == 0) {
      return;
    }
    long endExclusive = offset + length;
    long firstBlock = offset / blockSize;
    long lastBlock = (endExclusive - 1) / blockSize;

    int written = 0;
    for (long b = firstBlock; b <= lastBlock; b++) {
      byte[] block = loadBlock(b);
      long blockStartInFile = b * ((long) blockSize);
      int fromInBlock = (int) (Math.max(blockStartInFile, offset + written) - blockStartInFile);
      int toInBlock = (int) (Math.min(blockStartInFile + block.length, endExclusive) - blockStartInFile);
      int copyLen = toInBlock - fromInBlock;
      System.arraycopy(block, fromInBlock, dst, dstOffset + written, copyLen);
      written += copyLen;
    }
  }

  private byte[] loadBlock(long blockIndex) throws IOException {
    BlockKey key = new BlockKey(objectKey, blockIndex);
    byte[] block = cache.getIfPresent(key);
    if (block != null) {
      long startNanos = System.nanoTime();
      Counter.builder(METER_CACHE_HIT).register(meterRegistry).increment();
      Timer.builder(METER_GET_DURATION)
          .tag(TAG_CACHE_HIT, "true")
          .register(meterRegistry)
          .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      return block;
    }
    Counter.builder(METER_CACHE_MISS).register(meterRegistry).increment();
    long blockStart = blockIndex * ((long) blockSize);
    int len = (int) Math.min(blockSize, objectLength - blockStart);
    if (len <= 0) {
      throw new IOException(
          "block "
              + blockIndex
              + " is beyond object end (objectLength="
              + objectLength
              + ", blockStart="
              + blockStart
              + ")");
    }
    byte[] buf = new byte[len];
    underlying.readRange(blockStart, buf, 0, len);
    cache.put(key, buf);
    return buf;
  }
}
