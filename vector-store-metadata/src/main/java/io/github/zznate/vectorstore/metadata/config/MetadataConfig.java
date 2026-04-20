package io.github.zznate.vectorstore.metadata.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed binding for {@code vectorstore.metadata.*} configuration. Today
 * only the sidecar cache is configurable; phase 2 posting lists will add
 * their own keys under the same prefix.
 */
@ConfigMapping(prefix = "vectorstore.metadata")
public interface MetadataConfig {

  @WithName("sidecar-cache")
  SidecarCacheConfig sidecarCache();

  interface SidecarCacheConfig {

    /** Maximum on-heap size, in bytes, across every cached sidecar. */
    @WithDefault("134217728") // 128 MiB
    long bytes();
  }
}
