package io.github.zznate.vectorstore.metadata.filter;

import io.github.jbellis.jvector.util.Bits;
import org.roaringbitmap.RoaringBitmap;

/**
 * Adapts a {@link RoaringBitmap} to JVector's {@link Bits} interface so
 * the compiled filter (and the {@code filter AND NOT tombstones} combined
 * mask) can feed {@code GraphSearcher.search} directly. The bitmap is
 * stored by reference; callers must not mutate it after adapter
 * construction.
 */
public final class RoaringBitsAdapter implements Bits {

  private final RoaringBitmap bitmap;
  private final int length;

  public RoaringBitsAdapter(RoaringBitmap bitmap, int length) {
    this.bitmap = bitmap;
    this.length = length;
  }

  @Override
  public boolean get(int index) {
    return bitmap.contains(index);
  }

  public int length() {
    return length;
  }

  public RoaringBitmap bitmap() {
    return bitmap;
  }
}
