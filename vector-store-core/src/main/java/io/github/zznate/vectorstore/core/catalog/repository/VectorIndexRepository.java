package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import java.util.List;
import java.util.Optional;

public interface VectorIndexRepository {

  VectorIndex create(VectorIndex index);

  Optional<VectorIndex> findById(String indexId);

  List<VectorIndex> listByBucket(String bucketId);

  /**
   * Every index across every bucket, ordered by creation time. Drives
   * the startup catalog compatibility check; not currently exposed via
   * REST (the API lists per-bucket).
   */
  List<VectorIndex> listAll();

  void delete(String indexId);
}
