package io.github.zznate.vectorstore.metadata.sidecar;

import io.github.zznate.vectorstore.core.cache.CacheConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the process-wide {@link SidecarCache}. Size taken from
 * {@code vectorstore.cache.sidecar.bytes}. The cache delegates to a shared
 * {@code HeapCacheTier} so it participates in the standard cache-tier
 * metrics surface.
 */
@ApplicationScoped
public class SidecarCacheProducer {

  private static final Logger LOG = LoggerFactory.getLogger(SidecarCacheProducer.class);

  @Produces
  @Singleton
  public SidecarCache sidecarCache(CacheConfig config, MeterRegistry meterRegistry) {
    long bytes = config.sidecar().bytes();
    if (LOG.isInfoEnabled()) {
      LOG.info("Initialising sidecar cache budget={} bytes", bytes);
    }
    return new SidecarCache(bytes, meterRegistry);
  }
}
