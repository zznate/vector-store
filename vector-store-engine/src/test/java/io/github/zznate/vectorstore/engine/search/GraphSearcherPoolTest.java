package io.github.zznate.vectorstore.engine.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.testsupport.EngineTestHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphSearcherPoolTest {

  private EngineTestHarness harness;
  private OnDiskGraphIndex graph;

  @BeforeEach
  void setUp() throws Exception {
    harness = EngineTestHarness.create();
    Segment segment = buildSegment("seg-pool", 6);
    graph = harness.handles.get(segment).graph();
  }

  @AfterEach
  void tearDown() throws Exception {
    harness.close();
  }

  @Test
  void rejectsNegativeMaxIdle() {
    assertThatThrownBy(() -> new GraphSearcherPool(graph, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acquireOnEmptyPoolConstructsFreshSearcher() {
    GraphSearcherPool pool = new GraphSearcherPool(graph);
    GraphSearcher s = pool.acquire();
    assertThat(s).isNotNull();
    assertThat(pool.idleCount()).isZero();
    pool.release(s);
    pool.close();
  }

  @Test
  void releaseQueuesForReuseAndAcquireReturnsSameInstance() {
    GraphSearcherPool pool = new GraphSearcherPool(graph);
    GraphSearcher first = pool.acquire();
    pool.release(first);
    assertThat(pool.idleCount()).isEqualTo(1);

    GraphSearcher second = pool.acquire();
    assertThat(second).isSameAs(first);
    assertThat(pool.idleCount()).isZero();
    pool.release(second);
    pool.close();
  }

  @Test
  void releaseAtCapacityClosesInsteadOfQueueing() {
    GraphSearcherPool pool = new GraphSearcherPool(graph, 2);
    GraphSearcher a = pool.acquire();
    GraphSearcher b = pool.acquire();
    GraphSearcher c = pool.acquire();

    pool.release(a);
    pool.release(b);
    assertThat(pool.idleCount()).isEqualTo(2);

    pool.release(c); // capacity reached -> closed instead
    assertThat(pool.idleCount()).isEqualTo(2);

    pool.close();
  }

  @Test
  void closeDrainsIdleQueue() {
    GraphSearcherPool pool = new GraphSearcherPool(graph);
    GraphSearcher a = pool.acquire();
    GraphSearcher b = pool.acquire();
    pool.release(a);
    pool.release(b);
    assertThat(pool.idleCount()).isEqualTo(2);

    pool.close();
    assertThat(pool.idleCount()).isZero();
  }

  @Test
  void releaseAfterCloseClosesSearcherAndDoesNotQueue() {
    GraphSearcherPool pool = new GraphSearcherPool(graph);
    GraphSearcher a = pool.acquire();
    pool.close();

    pool.release(a); // pool is closed; release closes the searcher
    assertThat(pool.idleCount()).isZero();
  }

  @Test
  void acquireAfterCloseStillReturnsFreshSearcher() {
    GraphSearcherPool pool = new GraphSearcherPool(graph);
    pool.close();

    GraphSearcher s = pool.acquire();
    assertThat(s).isNotNull();
    pool.release(s); // closes since pool is closed
    assertThat(pool.idleCount()).isZero();
  }

  private Segment buildSegment(String segmentId, int size) throws Exception {
    List<BufferEntry> entries = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      float[] vec = new float[4];
      vec[i % 4] = 1f;
      entries.add(new BufferEntry("u" + i, vec, Map.of()));
    }
    return harness.buildAndPublish(
        "demo",
        "pool",
        segmentId,
        entries,
        4,
        DistanceMetric.COSINE,
        IndexBuildParams.defaults());
  }
}
