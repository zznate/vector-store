package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import java.util.List;
import java.util.Optional;

public interface SegmentRepository {

  Segment create(Segment segment);

  Optional<Segment> findById(String segmentId);

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
