package io.github.zznate.vectorstore.storage.reader;

import java.io.IOException;

/**
 * Narrow seam exposing "fill a byte range by absolute offset" as the only
 * cold-path operation the block cache needs. Implemented by
 * {@link S3RandomAccessReader} for production and by test doubles for
 * hermetic unit tests so the decorator can be validated without bringing up
 * an S3 client.
 */
public interface RangeReader extends AutoCloseable {

  /**
   * Fill {@code dst[dstOffset..dstOffset+length)} with the object bytes in
   * {@code [startOffset, startOffset+length)}. Implementations are expected
   * to be re-entrant across absolute offsets (no position state leaks).
   */
  void readRange(long startOffset, byte[] dst, int dstOffset, int length) throws IOException;

  @Override
  void close() throws IOException;
}
