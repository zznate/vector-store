package io.github.zznate.vectorstore.storage.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed binding for every {@code vectorstore.storage.*} config key. The S3
 * client producer, the reader supplier, and the segment store read from this
 * one mapping so the configuration surface stays flat and auditable.
 *
 * <p>Defaults target a local MinIO instance brought up via {@code
 * docker-compose up -d minio}. Credentials fall back to the built-in
 * {@code minioadmin} account but are overridable via environment.
 */
@ConfigMapping(prefix = "vectorstore.storage")
public interface StorageConfig {

  /** S3 endpoint URL, e.g. {@code http://localhost:9000} for local MinIO. */
  @WithDefault("http://localhost:9000")
  String endpoint();

  /** AWS region. MinIO ignores the value but the SDK requires one. */
  @WithDefault("us-east-1")
  String region();

  /** Bucket that holds every segment produced by this service. */
  @WithDefault("vectorstore")
  String bucket();

  @WithName("access-key")
  @WithDefault("minioadmin")
  String accessKey();

  @WithName("secret-key")
  @WithDefault("minioadmin")
  String secretKey();

  /**
   * MinIO and other S3-compatible stores that don't support virtual-host
   * style addressing need path-style. Real S3 works with either, so we leave
   * this on by default.
   */
  @WithName("path-style-access")
  @WithDefault("true")
  boolean pathStyleAccess();

  /** Block-cache configuration. Nested to keep the property tree tidy. */
  BlockCacheConfig blockCache();

  interface BlockCacheConfig {

    /** Maximum on-heap size of the block cache in bytes. Default 64 MiB. */
    @WithDefault("67108864")
    long bytes();

    /** Fixed block size in bytes used for caching and alignment. Default 64 KiB. */
    @WithName("block-size")
    @WithDefault("65536")
    int blockSize();

    /** Optional off-heap L2 tier for block bytes. Disabled by default. */
    L2Config l2();

    interface L2Config {

      /**
       * Enable the off-heap arena tier behind the on-heap block cache.
       * When false the block cache serves from L1 only and falls
       * straight through to the object store on miss.
       */
      @WithDefault("false")
      boolean enabled();

      /**
       * Maximum off-heap byte budget for the L2 tier. Default 256 MiB
       * — typically sized 4–8x larger than the L1 heap budget so warm
       * blocks survive longer between queries. Ignored when
       * {@link #enabled()} is false.
       */
      @WithDefault("268435456")
      long bytes();
    }
  }
}
