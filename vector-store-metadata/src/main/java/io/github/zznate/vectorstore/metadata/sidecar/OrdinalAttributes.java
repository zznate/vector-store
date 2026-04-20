package io.github.zznate.vectorstore.metadata.sidecar;

import java.util.Map;

/**
 * Read-only view over the ordinal &rarr; attributes mapping for one
 * segment. Introduced so the {@link
 * io.github.zznate.vectorstore.metadata.filter.FilterCompiler} can stay
 * unaware of how the sidecar was loaded (JSONL today, columnar / bitmap
 * indexes later). The parsed {@code AttributeSidecar} in phase 4
 * implements this interface; tests wire up hand-built instances directly.
 */
public interface OrdinalAttributes {

  /** Total number of ordinals in the segment. */
  int size();

  /**
   * Attributes for the given ordinal. Never {@code null}; an ordinal with
   * no attributes returns an empty map. An ordinal outside {@code [0, size)}
   * throws {@link IndexOutOfBoundsException}.
   */
  Map<String, String> attributesOf(int ordinal);
}
