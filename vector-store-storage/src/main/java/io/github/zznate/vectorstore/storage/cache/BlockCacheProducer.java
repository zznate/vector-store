package io.github.zznate.vectorstore.storage.cache;

import io.github.zznate.vectorstore.storage.config.StorageConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the singleton {@link BlockCache} shared across every reader in
 * the process. Sizing comes from {@code vectorstore.storage.block-cache.bytes}.
 */
@ApplicationScoped
public class BlockCacheProducer {

  private static final Logger LOG = LoggerFactory.getLogger(BlockCacheProducer.class);

  @Produces
  @Singleton
  public BlockCache blockCache(StorageConfig config) {
    long bytes = config.blockCache().bytes();
    int blockSize = config.blockCache().blockSize();
    if (LOG.isInfoEnabled()) {
      LOG.info("Initialising block cache budget={} bytes blockSize={} bytes", bytes, blockSize);
    }
    return new BlockCache(bytes);
  }
}
