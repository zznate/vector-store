package io.github.zznate.vectorstore.storage.cache;

import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.OffHeapArenaL2Provider;
import io.github.zznate.vectorstore.storage.config.StorageConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the singleton {@link BlockCache} shared across every reader in
 * the process. L1 sizing comes from
 * {@code vectorstore.storage.block-cache.bytes}; the optional L2 off-heap
 * arena tier is built when {@code vectorstore.storage.block-cache.l2.enabled}
 * is true and given a separate byte budget.
 */
@ApplicationScoped
public class BlockCacheProducer {

  private static final Logger LOG = LoggerFactory.getLogger(BlockCacheProducer.class);

  @Produces
  @Singleton
  public BlockCache blockCache(StorageConfig config, MeterRegistry meterRegistry) {
    long l1Bytes = config.blockCache().bytes();
    int blockSize = config.blockCache().blockSize();
    L2Provider l2 = maybeBuildL2(config, meterRegistry);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Initialising block cache l1Bytes={} blockSize={} l2={}",
          l1Bytes,
          blockSize,
          l2 == null ? "disabled" : ("enabled budget=" + config.blockCache().l2().bytes()));
    }
    return new BlockCache(l1Bytes, meterRegistry, l2);
  }

  /**
   * CDI disposer: when the {@link BlockCache} singleton is destroyed at
   * shutdown, release any native memory the L2 tier holds. Pinning this
   * to the BlockCache lifecycle (rather than producing the L2 provider
   * as its own bean) avoids the "L2 disabled means no bean to inject"
   * footgun.
   */
  public void disposeBlockCache(@Disposes BlockCache cache) {
    L2Provider l2 = cache.l2();
    if (l2 != null) {
      l2.close();
    }
  }

  private static L2Provider maybeBuildL2(StorageConfig config, MeterRegistry meterRegistry) {
    if (!config.blockCache().l2().enabled()) {
      return null;
    }
    return new OffHeapArenaL2Provider(
        config.blockCache().l2().bytes(), meterRegistry, BlockCache.CACHE_NAME);
  }
}
