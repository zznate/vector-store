package io.github.zznate.vectorstore.app.retention;

import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.core.retention.RetentionConfig;
import io.github.zznate.vectorstore.core.retention.RetentionSweep;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

@ApplicationScoped
public class RetentionSweepProducer {

  @Produces
  @Singleton
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public RetentionSweep retentionSweep(
      BucketRepository buckets,
      VectorIndexRepository indexes,
      SegmentRepository segments,
      ManifestVersionRepository manifests,
      SegmentStore segmentStore,
      ManifestCache manifestCache,
      RetentionConfig config,
      Clock clock) {
    return new RetentionSweep(
        buckets, indexes, segments, manifests, segmentStore, manifestCache, config, clock);
  }
}
