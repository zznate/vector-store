package io.github.zznate.vectorstore.metadata.posting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * On-disk format constants for {@code postings.bin} and the small
 * helpers (varint, FNV-1a 64-bit hash) shared by the writer and reader.
 *
 * <pre>
 *   header (32 bytes)
 *     magic       4   "PLST"
 *     version     4   0x00000001
 *     term_count  4   number of (key, value) entries
 *     index_off   8   byte offset of the index block
 *     data_off    8   byte offset of the data block
 *     reserved    4
 *
 *   index block (term_count * 40 bytes, sorted by (key_hash, value_hash))
 *     key_hash    8
 *     value_hash  8
 *     key_off     4   offset into string-pool
 *     value_off   4   offset into string-pool
 *     data_off    8   offset within data block
 *     data_len    8   serialized RoaringBitmap size
 *
 *   string-pool block
 *     varint-length-prefixed UTF-8 strings
 *
 *   data block
 *     concatenated serialized RoaringBitmap bytes (portable serialisation)
 * </pre>
 *
 * <p>All multi-byte integers are big-endian, consistent with JVector's
 * on-disk format and Java's default {@link java.io.DataOutput}/{@link
 * java.io.DataInput} conventions.
 */
public final class PostingListFormat {

  /** ASCII "PLST" packed big-endian. */
  public static final int MAGIC = 0x504C5354;

  public static final int VERSION = 1;

  public static final int HEADER_BYTES = 32;

  public static final int INDEX_ENTRY_BYTES = 40;

  private PostingListFormat() {}

  /**
   * 64-bit FNV-1a hash of a string's UTF-8 bytes. Deterministic and
   * cheap; the on-disk index uses it for sorted-search keys, with the
   * string pool resolving collisions on lookup.
   */
  public static long hash64(String s) {
    long h = 0xcbf29ce484222325L;
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    for (byte b : bytes) {
      h ^= (b & 0xffL);
      h *= 0x100000001b3L;
    }
    return h;
  }

  /**
   * Write an unsigned varint (LEB128, 7 bits per byte, MSB-continuation)
   * to {@code out}. Returns the number of bytes written so callers can
   * track the string-pool offset without re-tracking via the stream.
   */
  public static int writeVarint(OutputStream out, int value) throws IOException {
    if (value < 0) {
      throw new IllegalArgumentException("varint value must be non-negative: " + value);
    }
    int written = 0;
    int v = value;
    while ((v & ~0x7F) != 0) {
      out.write((v & 0x7F) | 0x80);
      v >>>= 7;
      written++;
    }
    out.write(v);
    return written + 1;
  }

  /** Decode an unsigned varint from {@code bytes} starting at {@code pos}. */
  public static VarintResult readVarint(byte[] bytes, int pos) {
    int result = 0;
    int shift = 0;
    int p = pos;
    while (true) {
      int b = bytes[p++] & 0xff;
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
      if (shift > 28) {
        throw new IllegalStateException("malformed varint at offset " + pos);
      }
    }
    return new VarintResult(result, p);
  }

  /** Decoded varint plus the offset just past it; emitted from {@link #readVarint}. */
  public record VarintResult(int value, int nextOffset) {}
}
