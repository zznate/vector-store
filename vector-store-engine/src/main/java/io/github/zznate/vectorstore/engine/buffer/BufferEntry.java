package io.github.zznate.vectorstore.engine.buffer;

import java.util.Map;
import java.util.Objects;

/**
 * One vector enqueued into the write buffer. Attributes are carried through
 * the builder for eventual persistence into the per-segment attribute
 * sidecar; the buffer keeps them alive but does not yet write them out.
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
