package io.github.zznate.vectorstore.metadata.sidecar;

import io.github.zznate.vectorstore.metadata.config.MetadataConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the process-wide {@link SidecarCache}. Size taken from
 * {@code vectorstore.metadata.sidecar-cache.bytes}. The cache delegates to
 * a shared {@code HeapCacheTier} so it participates in the standard
 * cache-tier metrics surface.
 */
@ApplicationScoped
public class SidecarCacheProducer {

  private static final Logger LOG = LoggerFactory.getLogger(SidecarCacheProducer.class);

  @Produces
  @Singleton
  public SidecarCache sidecarCache(MetadataConfig config, MeterRegistry meterRegistry) {
    long bytes = config.sidecarCache().bytes();
    if (LOG.isInfoEnabled()) {
      LOG.info("Initialising sidecar cache budget={} bytes", bytes);
    }
    return new SidecarCache(bytes, meterRegistry);
  }
}
