package io.github.zznate.vectorstore.metadata.posting;

import io.github.zznate.vectorstore.metadata.sidecar.CachedSidecar;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.roaringbitmap.RoaringBitmap;

/**
 * Reads a {@code postings.bin} sidecar into memory and resolves
 * per-{@code (key, value)} bitmaps on demand.
 *
 * <p>The full file is loaded into a single byte array (these sidecars
 * are small relative to the graph and the attribute sidecar). The header
 * and index are parsed eagerly so {@link #lookup(String, String)} is a
 * hashtable miss away from the bitmap bytes; bitmap deserialisation
 * itself stays lazy.
 *
 * <p>Implements {@link CachedSidecar} so the parsed structure shares
 * the byte-weighted budget with attribute and tombstone sidecars; the
 * cache key lives at {@link
 * io.github.zznate.vectorstore.metadata.sidecar.SidecarCache#postingsKey(String)}.
 */
public final class PostingListReader implements CachedSidecar {

  private final byte[] bytes;
  private final Map<TermKey, IndexEntry> entries;
  private final Set<String> indexedKeys;
  private final long dataOffset;

  private PostingListReader(
      byte[] bytes, Map<TermKey, IndexEntry> entries, Set<String> keys, long dataOffset) {
    this.bytes = bytes;
    this.entries = entries;
    this.indexedKeys = Collections.unmodifiableSet(keys);
    this.dataOffset = dataOffset;
  }

  /**
   * Read the full file into memory and parse the header + index. The
   * stream is consumed but not closed (caller-owned).
   */
  public static PostingListReader read(InputStream in) throws IOException {
    byte[] bytes = in.readAllBytes();
    return parse(bytes);
  }

  static PostingListReader parse(byte[] bytes) {
    if (bytes.length < PostingListFormat.HEADER_BYTES) {
      throw new IllegalArgumentException(
          "postings.bin shorter than header: " + bytes.length + " bytes");
    }
    ByteBuffer header = ByteBuffer.wrap(bytes, 0, PostingListFormat.HEADER_BYTES);
    int magic = header.getInt();
    int version = header.getInt();
    int termCount = header.getInt();
    long indexOff = header.getLong();
    long dataOff = header.getLong();
    if (magic != PostingListFormat.MAGIC) {
      throw new IllegalArgumentException(
          String.format("postings.bin bad magic: 0x%08X", magic));
    }
    if (version != PostingListFormat.VERSION) {
      throw new IllegalArgumentException(
          "postings.bin unsupported version: " + version);
    }
    long stringPoolOff =
        indexOff + (long) termCount * PostingListFormat.INDEX_ENTRY_BYTES;
    if (stringPoolOff > bytes.length || dataOff > bytes.length) {
      throw new IllegalArgumentException("postings.bin offsets exceed file size");
    }
    Map<TermKey, IndexEntry> entries = parseIndex(bytes, indexOff, termCount, stringPoolOff);
    Set<String> keys = new HashSet<>();
    for (TermKey tk : entries.keySet()) {
      keys.add(tk.key());
    }
    return new PostingListReader(bytes, entries, keys, dataOff);
  }

  private static Map<TermKey, IndexEntry> parseIndex(
      byte[] bytes, long indexOff, int termCount, long stringPoolOff) {
    Map<TermKey, IndexEntry> map = new LinkedHashMap<>(termCount * 2);
    ByteBuffer view = ByteBuffer.wrap(bytes);
    int origin = Math.toIntExact(indexOff);
    for (int i = 0; i < termCount; i++) {
      int base = origin + i * PostingListFormat.INDEX_ENTRY_BYTES;
      view.position(base);
      view.getLong(); // key_hash — not needed once strings are resolved
      view.getLong(); // value_hash
      int keyOff = view.getInt();
      int valueOff = view.getInt();
      long dataLocal = view.getLong();
      long dataLen = view.getLong();
      String key = readPoolString(bytes, stringPoolOff, keyOff);
      String value = readPoolString(bytes, stringPoolOff, valueOff);
      map.put(new TermKey(key, value), new IndexEntry(dataLocal, dataLen));
    }
    return map;
  }

  private static String readPoolString(byte[] bytes, long poolOff, int relativeOff) {
    int start = Math.toIntExact(poolOff + relativeOff);
    PostingListFormat.VarintResult vr = PostingListFormat.readVarint(bytes, start);
    return new String(bytes, vr.nextOffset(), vr.value(), StandardCharsets.UTF_8);
  }

  /** Resolve the bitmap for {@code (key, value)}; empty if absent. */
  public Optional<RoaringBitmap> lookup(String key, String value) {
    IndexEntry entry = entries.get(new TermKey(key, value));
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(deserialise(entry));
  }

  private RoaringBitmap deserialise(IndexEntry entry) {
    int absolute = Math.toIntExact(dataOffset + entry.dataOffset());
    int len = Math.toIntExact(entry.dataLen());
    byte[] slice = Arrays.copyOfRange(bytes, absolute, absolute + len);
    RoaringBitmap bitmap = new RoaringBitmap();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(slice))) {
      bitmap.deserialize(in);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to deserialise posting-list bitmap", e);
    }
    return bitmap;
  }

  /** Set of keys that have at least one indexed value. */
  public Set<String> indexedKeys() {
    return indexedKeys;
  }

  /** Number of {@code (key, value)} entries on disk. */
  public int termCount() {
    return entries.size();
  }

  /** Total bytes the reader is holding (including unparsed bitmap bytes). */
  @Override
  public long sizeBytes() {
    return bytes.length;
  }

  private record TermKey(String key, String value) {}

  private record IndexEntry(long dataOffset, long dataLen) {}
}
