package io.github.zznate.vectorstore.engine.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Stresses {@link InMemoryWriteBuffer} under concurrent producers and
 * periodic snapshotters. No append is allowed to disappear and no append
 * is allowed to appear twice — the invariant every write-then-commit
 * pipeline downstream of the buffer depends on.
 */
class InMemoryWriteBufferConcurrencyTest {

  private static final String INDEX_ID = "demo/stress";

  @Test
  void concurrentAppendsAndSnapshotsNeitherLoseNorDuplicate() throws Exception {
    InMemoryWriteBuffer buffer = new InMemoryWriteBuffer();
    int producerCount = 16;
    int appendsPerProducer = 1000;
    int batchSize = 5;

    ExecutorService producers = Executors.newFixedThreadPool(producerCount);
    ExecutorService snapshotter = Executors.newSingleThreadExecutor();
    CyclicBarrier startBarrier = new CyclicBarrier(producerCount + 1);

    List<BufferSnapshot> snapshots = new ArrayList<>();
    Object snapshotsLock = new Object();

    for (int p = 0; p < producerCount; p++) {
      int producerId = p;
      producers.submit(
          () -> {
            try {
              startBarrier.await();
              for (int i = 0; i < appendsPerProducer; i += batchSize) {
                List<BufferEntry> batch = new ArrayList<>(batchSize);
                for (int j = 0; j < batchSize && i + j < appendsPerProducer; j++) {
                  String id = "p" + producerId + "-i" + (i + j);
                  batch.add(new BufferEntry(id, new float[] {1.0f}, java.util.Map.of()));
                }
                buffer.append(INDEX_ID, batch);
              }
            } catch (InterruptedException | BrokenBarrierException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    snapshotter.submit(
        () -> {
          try {
            startBarrier.await();
            for (int i = 0; i < 8; i++) {
              Thread.sleep(5);
              BufferSnapshot snap = buffer.snapshotAndClear(INDEX_ID);
              synchronized (snapshotsLock) {
                snapshots.add(snap);
              }
            }
          } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
          }
        });

    producers.shutdown();
    assertThat(producers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    // One final snapshot after producers have quit to drain any remainder.
    BufferSnapshot tail = buffer.snapshotAndClear(INDEX_ID);
    synchronized (snapshotsLock) {
      snapshots.add(tail);
    }
    snapshotter.shutdown();
    assertThat(snapshotter.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    int expected = producerCount * appendsPerProducer;
    int totalSeen = snapshots.stream().mapToInt(BufferSnapshot::size).sum();
    assertThat(totalSeen).as("total entries across snapshots").isEqualTo(expected);

    Set<String> seenIds = new HashSet<>(expected);
    for (BufferSnapshot snapshot : snapshots) {
      for (BufferEntry entry : snapshot.entries()) {
        boolean added = seenIds.add(entry.userId());
        assertThat(added)
            .as("entry %s should appear in exactly one snapshot", entry.userId())
            .isTrue();
      }
    }
    assertThat(seenIds).hasSize(expected);
    assertThat(buffer.size(INDEX_ID)).isZero();
  }
}
