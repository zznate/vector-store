package io.github.zznate.vectorstore.storage.cache;

import io.github.zznate.vectorstore.core.cache.CacheConfig;
import io.github.zznate.vectorstore.core.cache.ChainedL2Provider;
import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.github.zznate.vectorstore.core.cache.SlabOffHeapL2Provider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the singleton {@link BlockCache} shared across every reader in
 * the process. L1 sizing comes from {@code vectorstore.cache.block.bytes}.
 * Two optional L2 tiers can sit behind L1, independently or together:
 *
 * <ul>
 *   <li>{@code vectorstore.cache.block.l2.*} — off-heap arena tier
 *       ({@link SlabOffHeapL2Provider}).</li>
 *   <li>{@code vectorstore.cache.block.l2-disk.*} — persistent disk tier
 *       ({@link LmdbL2Provider}).</li>
 * </ul>
 *
 * <p>When both tiers are enabled they compose behind a {@link
 * ChainedL2Provider} (off-heap first, then disk). Hits at any tier
 * promote upward.
 */
@ApplicationScoped
public class BlockCacheProducer {

  private static final Logger LOG = LoggerFactory.getLogger(BlockCacheProducer.class);

  @Produces
  @Singleton
  public BlockCache blockCache(CacheConfig config, MeterRegistry meterRegistry) {
    long l1Bytes = config.block().bytes();
    int blockSize = config.block().blockSize();
    L2Provider l2 = buildL2Chain(config, meterRegistry);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Initialising block cache l1Bytes={} blockSize={} l2={}",
          l1Bytes,
          blockSize,
          describeL2(config));
    }
    return new BlockCache(l1Bytes, meterRegistry, l2);
  }

  /**
   * CDI disposer: when the {@link BlockCache} singleton is destroyed at
   * shutdown, release any native memory the L2 tier holds. Pinning this
   * to the BlockCache lifecycle (rather than producing the L2 provider
   * as its own bean) avoids the "L2 disabled means no bean to inject"
   * footgun. {@link ChainedL2Provider#close()} cascades, so disk + off-
   * heap both release here.
   */
  public void disposeBlockCache(@Disposes BlockCache cache) {
    // L2 closes first, then L1. The two are independent resources (no
    // BlockCache mutation calls during shutdown) so order is correctness-
    // neutral; preserve the historical L2-first ordering for predictability.
    L2Provider l2 = cache.l2();
    if (l2 != null) {
      l2.close();
    }
    cache.tier().close();
  }

  /**
   * Build the L2 stack from configuration. Returns:
   *
   * <ul>
   *   <li>{@code null} if neither L2 tier is enabled.</li>
   *   <li>A bare {@link SlabOffHeapL2Provider} or {@link LmdbL2Provider}
   *       if exactly one is enabled.</li>
   *   <li>A {@link ChainedL2Provider} of {@code [offheap, disk]} if both are
   *       enabled — read path tries off-heap first, then disk.</li>
   * </ul>
   */
  private static L2Provider buildL2Chain(CacheConfig config, MeterRegistry meterRegistry) {
    boolean offheapEnabled = config.block().l2().enabled();
    boolean diskEnabled = config.block().l2Disk().enabled();
    if (!offheapEnabled && !diskEnabled) {
      return null;
    }
    List<L2Provider> tiers = new ArrayList<>(2);
    if (offheapEnabled) {
      tiers.add(
          new SlabOffHeapL2Provider(
              config.block().l2().bytes(),
              config.block().blockSize(),
              meterRegistry,
              BlockCache.CACHE_NAME));
    }
    if (diskEnabled) {
      Path path = ensureDiskPathWritable(config.block().l2Disk().path());
      tiers.add(
          new LmdbL2Provider(
              path, config.block().l2Disk().bytes(), meterRegistry, BlockCache.CACHE_NAME));
    }
    return tiers.size() == 1 ? tiers.get(0) : new ChainedL2Provider(tiers);
  }

  /**
   * Pre-flight check on the configured disk-tier directory. Creates it
   * if missing; throws an explicit {@link UncheckedIOException} if the
   * path exists but is not a writable directory, so the operator gets
   * a clear "fix this path" message rather than a downstream
   * {@code FileAlreadyExistsException} from inside the provider.
   */
  private static Path ensureDiskPathWritable(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "vectorstore.cache.block.l2-disk.path is not creatable: " + path, e);
    }
    if (!Files.isDirectory(path) || !Files.isWritable(path)) {
      throw new UncheckedIOException(
          "vectorstore.cache.block.l2-disk.path is not a writable directory: " + path,
          new IOException("not writable"));
    }
    return path;
  }

  private static String describeL2(CacheConfig config) {
    boolean offheap = config.block().l2().enabled();
    boolean disk = config.block().l2Disk().enabled();
    if (!offheap && !disk) {
      return "disabled";
    }
    StringBuilder sb = new StringBuilder("enabled");
    if (offheap) {
      sb.append(" offheap=").append(config.block().l2().bytes());
    }
    if (disk) {
      sb.append(" disk=").append(config.block().l2Disk().bytes())
          .append('@').append(config.block().l2Disk().path());
    }
    return sb.toString();
  }
}
