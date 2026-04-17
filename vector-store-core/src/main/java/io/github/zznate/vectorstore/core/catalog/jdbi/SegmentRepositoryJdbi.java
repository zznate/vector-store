package io.github.zznate.vectorstore.core.catalog.jdbi;

import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public class SegmentRepositoryJdbi implements SegmentRepository {

  private final Jdbi jdbi;

  @Inject
  public SegmentRepositoryJdbi(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  public Segment create(Segment segment) {
    jdbi.useExtension(SegmentDao.class, dao -> dao.insert(segment));
    return segment;
  }

  @Override
  public Optional<Segment> findById(String segmentId) {
    return jdbi.withExtension(SegmentDao.class, dao -> dao.findById(segmentId));
  }

  @Override
  public List<Segment> listByIndex(String indexId) {
    return jdbi.withExtension(SegmentDao.class, dao -> dao.listByIndex(indexId));
  }

  @Override
  public void updateState(String segmentId, SegmentState state) {
    jdbi.useExtension(SegmentDao.class, dao -> dao.updateState(segmentId, state));
  }

  @Override
  public void delete(String segmentId) {
    jdbi.useExtension(SegmentDao.class, dao -> dao.delete(segmentId));
  }
}
