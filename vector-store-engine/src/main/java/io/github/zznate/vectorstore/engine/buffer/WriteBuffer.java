package io.github.zznate.vectorstore.engine.buffer;

import java.util.List;

/**
 * Per-index, in-memory accumulator of vectors awaiting the next commit.
 * Thread-safe: concurrent {@link #append} calls from request threads may
 * interleave with each other and with {@link #snapshotAndClear} from the
 * commit coordinator. The commit coordinator snapshots a consistent view
 * of the buffer as it existed at the instant of the call and resets the
 * buffer to empty in the same atomic region, so in-flight appends that
 * race the snapshot land in the fresh buffer rather than being lost.
 *
 * <p>Not persistent. Vectors appended but not yet committed are lost on
 * restart.
 */
public interface WriteBuffer {

  /**
   * Append a batch of entries to {@code indexId}'s buffer. The list is
   * treated as opaque; the buffer makes no guarantees about in-batch
   * ordering beyond preserving the caller's order.
   */
  void append(String indexId, List<BufferEntry> batch);

  /** Current buffered entry count for {@code indexId}. */
  int size(String indexId);

  /**
   * Atomically hand ownership of the current buffer to the caller and
   * reset the in-memory state to empty. Returns an empty snapshot if the
   * buffer was already empty.
   */
  BufferSnapshot snapshotAndClear(String indexId);

  /**
   * Drop {@code indexId}'s buffer entirely. Called when an index is
   * deleted so the per-index map does not retain entries for indexes
   * that no longer exist. No-op if the index has no buffer.
   */
  void invalidateIndex(String indexId);
}
