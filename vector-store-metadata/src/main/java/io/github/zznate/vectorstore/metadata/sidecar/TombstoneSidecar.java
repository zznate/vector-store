package io.github.zznate.vectorstore.metadata.sidecar;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.roaringbitmap.RoaringBitmap;

/**
 * Per-segment tombstone bitmap. Stores ordinals (not user IDs) that have
 * been deleted; the query path drops these ordinals from the accept mask
 * after the filter is applied. Serialised form matches
 * {@link RoaringBitmap}'s portable {@code serialize(DataOutput)} format so
 * the bytes on S3 can be read back from any language with a RoaringBitmap
 * implementation.
 */
public final class TombstoneSidecar implements CachedSidecar {

  private final RoaringBitmap bitmap;

  public TombstoneSidecar(RoaringBitmap bitmap) {
    this.bitmap = bitmap;
  }

  /** Factory for the empty bitmap. */
  public static TombstoneSidecar empty() {
    return new TombstoneSidecar(new RoaringBitmap());
  }

  /**
   * Read the portable {@code RoaringBitmap} format. An empty stream (zero
   * bytes) is valid and yields an empty bitmap; higher layers rely on that
   * so an as-yet-unwritten {@code tombstones.roar} placeholder doesn't
   * error out on read.
   */
  public static TombstoneSidecar read(InputStream in) throws IOException {
    RoaringBitmap bitmap = new RoaringBitmap();
    byte[] all = in.readAllBytes();
    if (all.length == 0) {
      return new TombstoneSidecar(bitmap);
    }
    try (DataInputStream dis =
        new DataInputStream(new java.io.ByteArrayInputStream(all))) {
      bitmap.deserialize(dis);
    }
    return new TombstoneSidecar(bitmap);
  }

  /** Serialise to the portable {@code RoaringBitmap} byte form. */
  public byte[] toBytes() throws IOException {
    bitmap.runOptimize();
    ByteArrayOutputStream baos = new ByteArrayOutputStream(bitmap.serializedSizeInBytes());
    try (DataOutputStream dos = new DataOutputStream(baos)) {
      bitmap.serialize(dos);
    }
    return baos.toByteArray();
  }

  public RoaringBitmap bitmap() {
    return bitmap;
  }

  public boolean isEmpty() {
    return bitmap.isEmpty();
  }

  /**
   * Return a new sidecar holding {@code this.bitmap ∪ other}. The original
   * sidecar is not mutated; the commit path uses this to build a new
   * persisted sidecar without touching the cached instance the read path
   * sees.
   */
  public TombstoneSidecar mergedWith(RoaringBitmap other) {
    RoaringBitmap merged = bitmap.clone();
    merged.or(other);
    return new TombstoneSidecar(merged);
  }

  @Override
  public long sizeBytes() {
    return bitmap.serializedSizeInBytes();
  }
}
