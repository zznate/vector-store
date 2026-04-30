package io.github.zznate.vectorstore.core.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read / write the {@link LocalDiskL2Provider} index sidecar file. Lives
 * alongside the provider's data file and lets a clean restart skip the
 * cold-start fallback by re-attaching to the already-warm bytes on
 * disk.
 *
 * <p>Format (all integers big-endian):
 *
 * <pre>
 *   header  : magic(4) version(4) allocOffset(8) currentBytes(8) entryCount(4)   = 28 bytes
 *   entry   : keyLen(4) keyBytes(N) offset(8) length(4)                          = 16 + N bytes
 * </pre>
 *
 * <p>Validation is best-effort: any malformed structure falls through to
 * the caller as an empty {@link Optional}, which the provider treats as
 * a cold start.
 */
final class LocalDiskCacheIndex {

  private static final Logger LOG = LoggerFactory.getLogger(LocalDiskCacheIndex.class);

  /** Magic prefix — "VL2D" in big-endian ASCII. */
  static final int MAGIC = 0x564C3244;

  /** Format version. Bump when the on-disk layout changes incompatibly. */
  static final int VERSION = 1;

  /** Header bytes: magic + version + allocOffset + currentBytes + entryCount. */
  static final int HEADER_BYTES = 4 + 4 + 8 + 8 + 4;

  /** Per-entry fixed bytes: keyLen + offset + length (key bytes are variable). */
  static final int PER_ENTRY_FIXED_BYTES = 4 + 8 + 4;

  private LocalDiskCacheIndex() {}

  /** Snapshot of a successfully-loaded index. The provider adopts these fields verbatim. */
  record LoadedState(
      LinkedHashMap<String, LocalDiskL2Provider.DiskEntry> entries,
      long allocOffset,
      long currentBytes) {}

  /**
   * Attempt to load the sidecar at {@code indexFile}. Returns an empty
   * {@link Optional} when the file is missing, has the wrong magic /
   * version, is truncated, or contains entry references that fall
   * outside {@code maxBytes}. {@code cacheName} is only used for log
   * lines.
   */
  static Optional<LoadedState> tryLoad(Path indexFile, long maxBytes, String cacheName) {
    if (!Files.exists(indexFile)) {
      return Optional.empty();
    }
    try {
      ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(indexFile));
      Header header = readHeader(buf);
      LinkedHashMap<String, LocalDiskL2Provider.DiskEntry> reloaded =
          readEntries(buf, header.entryCount(), maxBytes);
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "L2 disk cache \"{}\" warm-restarted: entries={} currentBytes={}",
            cacheName,
            reloaded.size(),
            header.currentBytes());
      }
      return Optional.of(
          new LoadedState(reloaded, header.allocOffset(), header.currentBytes()));
    } catch (IOException | RuntimeException e) {
      // RuntimeException covers BufferUnderflowException + IndexFormatException.
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "L2 disk cache \"{}\" failed to reload index, starting cold", cacheName, e);
      }
      return Optional.empty();
    }
  }

  /**
   * Serialise the current state to {@code indexFile}. Writes to a temp
   * file first then atomic-renames so a crash mid-write cannot corrupt
   * the existing sidecar. Persistence failures are logged at WARN but
   * do not propagate — the caller's {@code close()} must always succeed.
   */
  static void persist(
      Path indexFile,
      Map<String, LocalDiskL2Provider.DiskEntry> entries,
      long allocOffset,
      long currentBytes,
      String cacheName) {
    try {
      ByteBuffer buf = serialise(entries, allocOffset, currentBytes);
      Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
      Files.write(
          tmp,
          buf.array(),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      Files.move(
          tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("L2 disk cache \"{}\" failed to persist index", cacheName, e);
      }
    }
  }

  // ---- internal ------------------------------------------------------

  private static ByteBuffer serialise(
      Map<String, LocalDiskL2Provider.DiskEntry> entries, long allocOffset, long currentBytes) {
    byte[][] keyBytes = new byte[entries.size()][];
    int totalBytes = HEADER_BYTES;
    int i = 0;
    for (String key : entries.keySet()) {
      keyBytes[i] = key.getBytes(StandardCharsets.UTF_8);
      totalBytes += PER_ENTRY_FIXED_BYTES + keyBytes[i].length;
      i++;
    }
    ByteBuffer buf = ByteBuffer.allocate(totalBytes);
    buf.putInt(MAGIC);
    buf.putInt(VERSION);
    buf.putLong(allocOffset);
    buf.putLong(currentBytes);
    buf.putInt(entries.size());
    i = 0;
    for (Map.Entry<String, LocalDiskL2Provider.DiskEntry> mapEntry : entries.entrySet()) {
      buf.putInt(keyBytes[i].length);
      buf.put(keyBytes[i]);
      buf.putLong(mapEntry.getValue().offset());
      buf.putInt(mapEntry.getValue().length());
      i++;
    }
    return buf;
  }

  private static Header readHeader(ByteBuffer buf) {
    if (buf.remaining() < HEADER_BYTES) {
      throw new IndexFormatException("index sidecar too short");
    }
    int magic = buf.getInt();
    int version = buf.getInt();
    if (magic != MAGIC || version != VERSION) {
      throw new IndexFormatException("magic / version mismatch");
    }
    return new Header(buf.getLong(), buf.getLong(), buf.getInt());
  }

  private static LinkedHashMap<String, LocalDiskL2Provider.DiskEntry> readEntries(
      ByteBuffer buf, int entryCount, long maxBytes) {
    LinkedHashMap<String, LocalDiskL2Provider.DiskEntry> reloaded =
        new LinkedHashMap<>(entryCount * 2, 0.75f, /* accessOrder */ true);
    for (int i = 0; i < entryCount; i++) {
      Map.Entry<String, LocalDiskL2Provider.DiskEntry> next = readSingleEntry(buf, i, maxBytes);
      reloaded.put(next.getKey(), next.getValue());
    }
    return reloaded;
  }

  private static Map.Entry<String, LocalDiskL2Provider.DiskEntry> readSingleEntry(
      ByteBuffer buf, int index, long maxBytes) {
    if (buf.remaining() < 4) {
      throw new IndexFormatException("truncated entry header at i=" + index);
    }
    int keyLen = buf.getInt();
    if (!validKeyLen(keyLen, buf)) {
      throw new IndexFormatException("invalid key length " + keyLen + " at i=" + index);
    }
    byte[] keyBytes = new byte[keyLen];
    buf.get(keyBytes);
    // keyLen has already been consumed (4 bytes); the remaining fixed bytes are offset + length.
    if (buf.remaining() < PER_ENTRY_FIXED_BYTES - 4) {
      throw new IndexFormatException("truncated entry payload at i=" + index);
    }
    long offset = buf.getLong();
    int length = buf.getInt();
    if (!validEntryRange(offset, length, maxBytes)) {
      throw new IndexFormatException(
          "entry out of bounds offset=" + offset + " length=" + length);
    }
    return Map.entry(
        new String(keyBytes, StandardCharsets.UTF_8),
        new LocalDiskL2Provider.DiskEntry(offset, length));
  }

  private static boolean validKeyLen(int keyLen, ByteBuffer buf) {
    return keyLen >= 0 && keyLen <= buf.remaining();
  }

  private static boolean validEntryRange(long offset, int length, long maxBytes) {
    return offset >= 0 && length >= 0 && offset + length <= maxBytes;
  }

  private record Header(long allocOffset, long currentBytes, int entryCount) {}

  /** Internal signal that the sidecar is malformed; caught by the top-level loader. */
  private static final class IndexFormatException extends RuntimeException {
    IndexFormatException(String message) {
      super(message);
    }
  }
}
