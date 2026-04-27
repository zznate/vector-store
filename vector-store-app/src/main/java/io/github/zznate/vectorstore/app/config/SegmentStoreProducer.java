package io.github.zznate.vectorstore.app.config;

import io.github.zznate.vectorstore.core.cache.CacheConfig;
import io.github.zznate.vectorstore.core.cache.CachePolicyResolver;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.engine.store.LocalSegmentStore;
import io.github.zznate.vectorstore.storage.S3SegmentStore;
import io.github.zznate.vectorstore.storage.cache.BlockCache;
import io.github.zznate.vectorstore.storage.config.StorageConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Produces the runtime {@link SegmentStore}, dispatching on
 * {@code vectorstore.segments.store}:
 *
 * <ul>
 *   <li>{@code s3} (default) — phase-3 {@link S3SegmentStore} against MinIO
 *       or real S3. All upstream dependencies are injected via
 *       {@link Instance} so local-only runs don't force the S3 client or
 *       storage config to resolve.</li>
 *   <li>{@code local} — phase-2 {@link LocalSegmentStore} rooted at
 *       {@code vectorstore.segments.root}. Intended for developers who want
 *       to work without bringing up MinIO via docker-compose and for tests
 *       that don't need object-store fidelity.</li>
 * </ul>
 */
@ApplicationScoped
public class SegmentStoreProducer {

  private static final Logger LOG = LoggerFactory.getLogger(SegmentStoreProducer.class);

  private static final String STORE_S3 = "s3";
  private static final String STORE_LOCAL = "local";

  @ConfigProperty(name = "vectorstore.segments.store", defaultValue = STORE_S3)
  String storeKind;

  @ConfigProperty(name = "vectorstore.segments.root")
  String localRoot;

  @jakarta.inject.Inject Instance<S3Client> s3Client;
  @jakarta.inject.Inject Instance<StorageConfig> storageConfig;
  @jakarta.inject.Inject Instance<CacheConfig> cacheConfig;
  @jakarta.inject.Inject Instance<BlockCache> blockCache;
  @jakarta.inject.Inject Instance<CachePolicyResolver> cachePolicyResolver;
  @jakarta.inject.Inject MeterRegistry meterRegistry;
  @jakarta.inject.Inject Tracer tracer;

  @Produces
  @Singleton
  public SegmentStore segmentStore() {
    String kind = storeKind == null ? STORE_S3 : storeKind.toLowerCase();
    return switch (kind) {
      case STORE_LOCAL -> buildLocal();
      case STORE_S3 -> buildS3();
      default -> throw new IllegalStateException(
          "unknown vectorstore.segments.store=" + storeKind + " (expected s3|local)");
    };
  }

  private LocalSegmentStore buildLocal() {
    Path root = Path.of(localRoot).toAbsolutePath();
    if (LOG.isInfoEnabled()) {
      LOG.info("Segment store mode=local root={}", root);
    }
    return new LocalSegmentStore(root);
  }

  private S3SegmentStore buildS3() {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Segment store mode=s3 bucket={} endpoint={}",
          storageConfig.get().bucket(),
          storageConfig.get().endpoint());
    }
    return new S3SegmentStore(
        s3Client.get(),
        storageConfig.get(),
        cacheConfig.get().block().blockSize(),
        blockCache.get(),
        cachePolicyResolver.get(),
        meterRegistry,
        tracer);
  }

  public void closeSegmentStore(@Disposes SegmentStore store) {
    if (store instanceof LocalSegmentStore local) {
      local.close();
    } else if (store instanceof S3SegmentStore s3) {
      s3.close();
    }
  }
}
