package io.github.zznate.vectorstore.core.cache;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes several {@link L2Provider} tiers behind a single
 * {@code L2Provider} reference so {@link
 * io.github.zznate.vectorstore.core.cache.HeapCacheTier}-paired callers
 * (today {@code BlockCache}) can ignore the multi-tier shape.
 *
 * <p>Read order on {@link #get(String)}: the providers list, head to
 * tail. The convention is "fastest first" — typically off-heap before
 * disk. On a hit from a lower tier, the value is promoted to every
 * tier above it so subsequent reads stay on the cheaper path.
 *
 * <p>Writes are write-through to every tier. Demotion-on-eviction
 * would be cleaner but requires explicit upward callbacks from each
 * tier; write-through achieves the same observable result (a hot key
 * present in upper tiers, a less-hot key present at least in lower
 * tiers) at the cost of duplicate writes that a real workload only
 * pays once per key.
 *
 * <p>{@link #invalidate(String)} and {@link #invalidateAll()} cascade
 * to every tier. {@link #close()} closes every tier, swallowing any
 * single tier's failure so a misbehaving provider does not leak the
 * others.
 *
 * <p>The chain registers no metrics of its own. Each underlying
 * provider tags its own counters with its own {@code tier} value
 * (e.g. {@code l2_offheap}, {@code l2_disk}); the chain itself is
 * a pass-through and would only duplicate those counts under a third
 * tag value.
 */
public final class ChainedL2Provider implements L2Provider {

  private static final Logger LOG = LoggerFactory.getLogger(ChainedL2Provider.class);

  public static final String TIER_NAME = "chained";

  private final List<L2Provider> providers;

  public ChainedL2Provider(List<L2Provider> providers) {
    Objects.requireNonNull(providers, "providers");
    if (providers.isEmpty()) {
      throw new IllegalArgumentException("ChainedL2Provider requires at least one provider");
    }
    this.providers = List.copyOf(providers);
  }

  @Override
  public Optional<byte[]> get(String key) {
    for (int i = 0; i < providers.size(); i++) {
      Optional<byte[]> hit = providers.get(i).get(key);
      if (hit.isPresent()) {
        promoteUpward(i, key, hit.get());
        return hit;
      }
    }
    return Optional.empty();
  }

  @Override
  public void put(String key, byte[] bytes) {
    for (L2Provider provider : providers) {
      provider.put(key, bytes);
    }
  }

  @Override
  public void invalidate(String key) {
    for (L2Provider provider : providers) {
      provider.invalidate(key);
    }
  }

  @Override
  public void invalidateAll() {
    for (L2Provider provider : providers) {
      provider.invalidateAll();
    }
  }

  @Override
  public CacheTierStats stats() {
    long hitCount = 0;
    long missCount = 0;
    long evictionCount = 0;
    long currentBytes = 0;
    long maxBytes = 0;
    long currentEntries = 0;
    for (L2Provider provider : providers) {
      CacheTierStats s = provider.stats();
      hitCount += s.hitCount();
      missCount += s.missCount();
      evictionCount += s.evictionCount();
      currentBytes += s.currentBytes();
      maxBytes += s.maxBytes();
      currentEntries += s.currentEntries();
    }
    return new CacheTierStats(
        hitCount, missCount, evictionCount, currentBytes, maxBytes, currentEntries);
  }

  @Override
  public String tierName() {
    return TIER_NAME;
  }

  @Override
  public void close() {
    for (L2Provider provider : providers) {
      try {
        provider.close();
      } catch (RuntimeException e) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("failed to close chained L2 provider {}", provider.tierName(), e);
        }
      }
    }
  }

  /** Underlying providers, in chain order. Exposed for stats / diagnostic inspection. */
  public List<L2Provider> providers() {
    return providers;
  }

  /**
   * After a hit from {@code providers[hitIndex]}, populate every tier
   * strictly above it so the next read of {@code key} stays on the
   * faster path.
   */
  private void promoteUpward(int hitIndex, String key, byte[] bytes) {
    for (int i = 0; i < hitIndex; i++) {
      providers.get(i).put(key, bytes);
    }
  }
}
