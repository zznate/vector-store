package io.github.zznate.vectorstore.engine.buffer;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of an index's write buffer at the moment a commit was
 * requested. Ownership transfers from the buffer to the commit pipeline;
 * the buffer is reset to empty inside the same lock region.
 */
public record BufferSnapshot(String indexId, List<BufferEntry> entries) {

  public BufferSnapshot {
    Objects.requireNonNull(indexId, "indexId");
    Objects.requireNonNull(entries, "entries");
    entries = List.copyOf(entries);
  }

  public int size() {
    return entries.size();
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }
}
