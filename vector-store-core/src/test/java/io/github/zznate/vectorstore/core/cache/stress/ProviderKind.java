package io.github.zznate.vectorstore.core.cache.stress;

/**
 * Identifies which {@code L2Provider} configuration the harness is
 * exercising. Scenarios use this to vary {@code maxBytes} and working-
 * set sizing per provider — eviction-churn in particular is sensitive
 * to LMDB's copy-on-write transient pressure (needs more headroom than
 * the slab tier).
 */
public enum ProviderKind {
  SLAB,
  LMDB,
  CHAIN
}
