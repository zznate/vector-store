package io.github.zznate.vectorstore.core.cache;

import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the {@link CachePolicy} (and optional {@code cacheBytes} hint)
 * for a given index id by reading the engine-params JSON column on
 * {@code vector_index} and parsing it through {@link IndexBuildParams}.
 *
 * <p>The hot query path consults the resolver once per query, so an
 * in-memory map fronts the catalog. Entries are populated lazily on first
 * lookup and live for the index's lifetime; callers must
 * {@link #invalidate} on index deletion to drop the dangling entry.
 *
 * <p>Mutating an existing index's cache policy requires explicit
 * invalidation by the calling resource — there is no PATCH path on the
 * REST surface today.
 */
@ApplicationScoped
public class CachePolicyResolver {

  private final VectorIndexRepository indexes;
  private final ConcurrentMap<String, CachedPolicy> byIndex = new ConcurrentHashMap<>();

  @Inject
  public CachePolicyResolver(VectorIndexRepository indexes) {
    this.indexes = indexes;
  }

  /**
   * Returns the cache policy for {@code indexId}. Throws
   * {@link IllegalArgumentException} if the index does not exist in the
   * catalog.
   */
  public CachePolicy policyFor(String indexId) {
    return resolve(indexId).policy();
  }

  /** Returns the optional per-index byte budget hint, or empty when unset. */
  public Optional<Long> cacheBytesFor(String indexId) {
    return Optional.ofNullable(resolve(indexId).cacheBytes());
  }

  /** Drop the cached policy for {@code indexId}. Idempotent. */
  public void invalidate(String indexId) {
    byIndex.remove(indexId);
  }

  /** Drop every cached policy. */
  public void invalidateAll() {
    byIndex.clear();
  }

  private CachedPolicy resolve(String indexId) {
    Objects.requireNonNull(indexId, "indexId");
    return byIndex.computeIfAbsent(indexId, this::loadFromCatalog);
  }

  private CachedPolicy loadFromCatalog(String indexId) {
    VectorIndex index =
        indexes
            .findById(indexId)
            .orElseThrow(() -> new IllegalArgumentException("no such index: " + indexId));
    IndexBuildParams params = IndexBuildParams.fromJson(index.engineParams());
    return new CachedPolicy(params.cachePolicy(), params.cacheBytes());
  }

  private record CachedPolicy(CachePolicy policy, Long cacheBytes) {}
}
