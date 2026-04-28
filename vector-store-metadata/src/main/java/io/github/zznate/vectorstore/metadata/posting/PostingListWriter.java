package io.github.zznate.vectorstore.metadata.posting;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.roaringbitmap.RoaringBitmap;

/**
 * Builds the per-segment {@code postings.bin} sidecar from the same
 * ordinal-to-attributes view that drives {@link
 * io.github.zznate.vectorstore.metadata.sidecar.AttributeSidecarWriter}.
 *
 * <p>Per-key cardinality is capped: keys with strictly more distinct
 * values than the cap are skipped — filters against those keys fall
 * back to the brute-force scan strategy at query time. Skipped keys are
 * surfaced via {@link WriteResult#skippedKeys()} so callers can log
 * once.
 */
public final class PostingListWriter {

  private PostingListWriter() {}

  /**
   * Write {@code postings.bin} into {@code target}. Returns size in
   * bytes and the set of high-cardinality keys that were skipped.
   *
   * @param target destination path; created or overwritten
   * @param byOrdinal dense view: position {@code i} holds the
   *     attributes attached to ordinal {@code i}
   * @param maxCardinality keys with more than this many distinct values
   *     are skipped
   */
  public static WriteResult write(
      Path target, List<Map<String, String>> byOrdinal, int maxCardinality) throws IOException {
    BuildResult build = buildPostings(byOrdinal, maxCardinality);
    long bytes = serialise(target, build.entries());
    return new WriteResult(bytes, Collections.unmodifiableSet(build.skippedKeys()));
  }

  private static BuildResult buildPostings(
      List<Map<String, String>> byOrdinal, int maxCardinality) {
    Map<String, Map<String, RoaringBitmap>> byKey = new LinkedHashMap<>();
    for (int ordinal = 0; ordinal < byOrdinal.size(); ordinal++) {
      Map<String, String> attrs = byOrdinal.get(ordinal);
      if (attrs == null) {
        continue;
      }
      for (Map.Entry<String, String> entry : attrs.entrySet()) {
        Map<String, RoaringBitmap> values =
            byKey.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>());
        values.computeIfAbsent(entry.getValue(), v -> new RoaringBitmap()).add(ordinal);
      }
    }

    Set<String> skipped = new TreeSet<>();
    List<TermEntry> entries = new ArrayList<>();
    for (Map.Entry<String, Map<String, RoaringBitmap>> e : byKey.entrySet()) {
      String key = e.getKey();
      Map<String, RoaringBitmap> values = e.getValue();
      if (values.size() > maxCardinality) {
        skipped.add(key);
        continue;
      }
      for (Map.Entry<String, RoaringBitmap> v : values.entrySet()) {
        RoaringBitmap bm = v.getValue();
        bm.runOptimize();
        entries.add(new TermEntry(key, v.getKey(), bm));
      }
    }
    entries.sort(
        (a, b) -> {
          int kc = Long.compareUnsigned(a.keyHash, b.keyHash);
          if (kc != 0) {
            return kc;
          }
          return Long.compareUnsigned(a.valueHash, b.valueHash);
        });
    return new BuildResult(entries, skipped);
  }

  private static long serialise(Path target, List<TermEntry> entries) throws IOException {
    StringPool pool = buildStringPool(entries);
    SerialisedEntries serialised = serialiseBitmaps(entries);

    long indexOff = PostingListFormat.HEADER_BYTES;
    long stringPoolOff =
        indexOff + (long) entries.size() * PostingListFormat.INDEX_ENTRY_BYTES;
    long dataOff = stringPoolOff + pool.totalBytes;

    try (OutputStream raw = Files.newOutputStream(target);
        BufferedOutputStream out = new BufferedOutputStream(raw);
        DataOutputStream dos = new DataOutputStream(out)) {
      writeHeader(dos, entries.size(), indexOff, dataOff);
      writeIndex(dos, entries, pool, serialised);
      writeStringPool(dos, pool);
      writeDataBlock(dos, serialised);
      dos.flush();
    }
    return Files.size(target);
  }

  private static void writeHeader(
      DataOutputStream dos, int termCount, long indexOff, long dataOff) throws IOException {
    dos.writeInt(PostingListFormat.MAGIC);
    dos.writeInt(PostingListFormat.VERSION);
    dos.writeInt(termCount);
    dos.writeLong(indexOff);
    dos.writeLong(dataOff);
    dos.writeInt(0); // reserved
  }

  private static void writeIndex(
      DataOutputStream dos,
      List<TermEntry> entries,
      StringPool pool,
      SerialisedEntries serialised)
      throws IOException {
    for (int i = 0; i < entries.size(); i++) {
      TermEntry e = entries.get(i);
      SerialisedBitmap sb = serialised.bitmaps.get(i);
      dos.writeLong(e.keyHash);
      dos.writeLong(e.valueHash);
      dos.writeInt(pool.offsets.get(e.key));
      dos.writeInt(pool.offsets.get(e.value));
      dos.writeLong(sb.dataOffset);
      dos.writeLong(sb.bytes.length);
    }
  }

  private static void writeStringPool(DataOutputStream dos, StringPool pool) throws IOException {
    dos.write(pool.bytes);
  }

  private static void writeDataBlock(DataOutputStream dos, SerialisedEntries serialised)
      throws IOException {
    for (SerialisedBitmap sb : serialised.bitmaps) {
      dos.write(sb.bytes);
    }
  }

  private static StringPool buildStringPool(List<TermEntry> entries) throws IOException {
    Map<String, Integer> offsets = new HashMap<>();
    ByteArrayOutputStream pool = new ByteArrayOutputStream();
    for (TermEntry e : entries) {
      internString(pool, offsets, e.key);
      internString(pool, offsets, e.value);
    }
    byte[] bytes = pool.toByteArray();
    return new StringPool(offsets, bytes, bytes.length);
  }

  private static void internString(
      ByteArrayOutputStream pool, Map<String, Integer> offsets, String s) throws IOException {
    if (offsets.containsKey(s)) {
      return;
    }
    int offset = pool.size();
    byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
    PostingListFormat.writeVarint(pool, utf8.length);
    pool.write(utf8);
    offsets.put(s, offset);
  }

  private static SerialisedEntries serialiseBitmaps(List<TermEntry> entries) throws IOException {
    long offset = 0;
    List<SerialisedBitmap> bitmaps = new ArrayList<>(entries.size());
    for (TermEntry e : entries) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream(e.bitmap.serializedSizeInBytes());
      try (DataOutputStream sink = new DataOutputStream(bos)) {
        e.bitmap.serialize(sink);
      }
      byte[] bytes = bos.toByteArray();
      bitmaps.add(new SerialisedBitmap(offset, bytes));
      offset += bytes.length;
    }
    return new SerialisedEntries(bitmaps);
  }

  private static final class TermEntry {
    final String key;
    final String value;
    final RoaringBitmap bitmap;
    final long keyHash;
    final long valueHash;

    TermEntry(String key, String value, RoaringBitmap bitmap) {
      this.key = key;
      this.value = value;
      this.bitmap = bitmap;
      this.keyHash = PostingListFormat.hash64(key);
      this.valueHash = PostingListFormat.hash64(value);
    }
  }

  private record BuildResult(List<TermEntry> entries, Set<String> skippedKeys) {}

  private record StringPool(Map<String, Integer> offsets, byte[] bytes, int totalBytes) {}

  private record SerialisedBitmap(long dataOffset, byte[] bytes) {}

  private record SerialisedEntries(List<SerialisedBitmap> bitmaps) {}

  /** Outcome of a successful write: byte size on disk plus skipped high-cardinality keys. */
  public record WriteResult(long bytesWritten, Set<String> skippedKeys) {}
}
