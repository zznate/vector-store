package io.github.zznate.vectorstore.core.catalog.repository;

import io.github.zznate.vectorstore.core.catalog.model.Bucket;
import java.util.List;
import java.util.Optional;

public interface BucketRepository {

  Bucket create(Bucket bucket);

  Optional<Bucket> findById(String bucketId);

  List<Bucket> list();

  void delete(String bucketId);
}
