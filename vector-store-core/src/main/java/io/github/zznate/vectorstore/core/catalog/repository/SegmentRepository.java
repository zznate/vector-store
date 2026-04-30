package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import java.util.List;
import java.util.Optional;

public interface SegmentRepository {

  Segment create(Segment segment);

  Optional<Segment> findById(String segmentId);

  /**
   * Every segment recorded for {@code indexId}, ordered by
   * {@code created_at}.
   *
   * <p>Caller invariant: <b>diagnostic / test-only</b>. Production
   * query path resolves the active segment list per manifest version
   * via {@link io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache};
   * production commit / compaction operates on the segments named by a
   * manifest, not on this raw view. Capped at 4096 rows in SQL as a
   * safety net so accidental production use of this method cannot OOM
   * the JVM.
   */
  List<Segment> listByIndex(String indexId);

  void updateState(String segmentId, SegmentState state);

  /**
   * Update both the segment's {@code state} and its recorded on-disk
   * {@code bytes} in a single statement. Used at the end of a successful
   * commit when the builder's actual byte count is finally known.
   */
  void updateStateAndBytes(String segmentId, SegmentState state, long bytes);

  void delete(String segmentId);
}
