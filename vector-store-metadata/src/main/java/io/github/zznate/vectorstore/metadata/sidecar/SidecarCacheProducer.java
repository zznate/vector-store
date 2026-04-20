package io.github.zznate.vectorstore.metadata.sidecar;

import io.github.zznate.vectorstore.metadata.config.MetadataConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the process-wide {@link SidecarCache}. Size taken from
 * {@code vectorstore.metadata.sidecar-cache.bytes}. The cache is kept
 * simple on purpose: phase-2 multi-tier caching (disk, Redis) will layer
 * behind this same type without a signature change.
 */
@ApplicationScoped
public class SidecarCacheProducer {

  private static final Logger LOG = LoggerFactory.getLogger(SidecarCacheProducer.class);

  @Produces
  @Singleton
  public SidecarCache sidecarCache(MetadataConfig config) {
    long bytes = config.sidecarCache().bytes();
    if (LOG.isInfoEnabled()) {
      LOG.info("Initialising sidecar cache budget={} bytes", bytes);
    }
    return new SidecarCache(bytes);
  }
}
