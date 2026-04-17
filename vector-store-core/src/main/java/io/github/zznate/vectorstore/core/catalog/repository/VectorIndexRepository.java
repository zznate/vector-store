package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import java.util.List;
import java.util.Optional;

public interface VectorIndexRepository {

  VectorIndex create(VectorIndex index);

  Optional<VectorIndex> findById(String indexId);

  List<VectorIndex> listByBucket(String bucketId);

  void delete(String indexId);
}
