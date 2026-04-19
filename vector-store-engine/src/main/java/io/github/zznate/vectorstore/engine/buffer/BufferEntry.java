package io.github.zznate.vectorstore.engine.buffer;

import java.util.Map;
import java.util.Objects;

/**
 * One vector enqueued into the write buffer. Attributes are carried through
 * the builder for eventual persistence into the per-segment attribute
 * sidecar; Phase 2 does not yet do anything with them beyond keeping them
 * alive for Phase 4 to pick up.
 */
public record BufferEntry(String userId, float[] vector, Map<String, String> attributes) {

  public BufferEntry {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(vector, "vector");
    if (vector.length == 0) {
      throw new IllegalArgumentException("vector must not be empty");
    }
  }
}
