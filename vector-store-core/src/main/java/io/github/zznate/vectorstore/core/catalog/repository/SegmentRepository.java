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

  void delete(String segmentId);
}
