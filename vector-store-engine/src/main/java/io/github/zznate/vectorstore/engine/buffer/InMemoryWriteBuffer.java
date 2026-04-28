package io.github.zznate.vectorstore.engine.buffer;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default in-memory {@link WriteBuffer}. One {@link ReentrantLock} per
 * index protects its entry list; different indexes never contend with each
 * other. Lost on process restart.
 */
@ApplicationScoped
public class InMemoryWriteBuffer implements WriteBuffer {

  private final Map<String, PerIndex> buffers = new ConcurrentHashMap<>();

  @Override
  public void append(String indexId, List<BufferEntry> batch) {
    buffers.computeIfAbsent(indexId, k -> new PerIndex()).append(batch);
  }

  @Override
  public int size(String indexId) {
    PerIndex p = buffers.get(indexId);
    return p == null ? 0 : p.size();
  }

  @Override
  public BufferSnapshot snapshotAndClear(String indexId) {
    PerIndex p = buffers.get(indexId);
    if (p == null) {
      return new BufferSnapshot(indexId, List.of());
    }
    return p.snapshotAndClear(indexId);
  }

  @Override
  public void invalidateIndex(String indexId) {
    buffers.remove(indexId);
  }

  private static final class PerIndex {

    private final ReentrantLock lock = new ReentrantLock();
    private List<BufferEntry> entries = new ArrayList<>();

    void append(List<BufferEntry> batch) {
      lock.lock();
      try {
        entries.addAll(batch);
      } finally {
        lock.unlock();
      }
    }

    int size() {
      lock.lock();
      try {
        return entries.size();
      } finally {
        lock.unlock();
      }
    }

    BufferSnapshot snapshotAndClear(String indexId) {
      lock.lock();
      try {
        List<BufferEntry> taken = entries;
        entries = new ArrayList<>();
        return new BufferSnapshot(indexId, taken);
      } finally {
        lock.unlock();
      }
    }
  }
}
