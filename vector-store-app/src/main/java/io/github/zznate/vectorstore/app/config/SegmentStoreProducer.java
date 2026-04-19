package io.github.zznate.vectorstore.app.config;

import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.engine.store.LocalSegmentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the runtime {@link SegmentStore}. Phase 2 wires in
 * {@link LocalSegmentStore} rooted at
 * {@code vectorstore.segments.root}; phase 3 will switch to an S3-backed
 * implementation without changing any consumer.
 */
@ApplicationScoped
public class SegmentStoreProducer {

  private static final Logger LOG = LoggerFactory.getLogger(SegmentStoreProducer.class);

  @ConfigProperty(name = "vectorstore.segments.root")
  String segmentsRoot;

  @Produces
  @Singleton
  public SegmentStore segmentStore() {
    Path root = Path.of(segmentsRoot).toAbsolutePath();
    if (LOG.isInfoEnabled()) {
      LOG.info("Local segment store rooted at {}", root);
    }
    return new LocalSegmentStore(root);
  }

  public void closeSegmentStore(@Disposes SegmentStore store) {
    if (store instanceof LocalSegmentStore local) {
      local.close();
    }
  }
}
