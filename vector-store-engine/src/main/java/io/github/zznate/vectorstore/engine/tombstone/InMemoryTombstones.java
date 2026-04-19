package io.github.zznate.vectorstore.engine.tombstone;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-index in-memory set of tombstoned user IDs. The query path consults
 * this set when building the per-segment accept mask so a tombstoned ID
 * never appears in results.
 *
 * <p>TODO(phase-4: persist): phase 4 writes tombstone bits into each
 * segment's {@code tombstones.roar} sidecar and folds them into the
 * accept mask at segment-open time. Until then, deletes are lost on
 * process restart. Any consumer of this class should assume restart
 * tolerance is zero and should be adjusted along with the phase-4
 * migration that makes tombstones durable.
 */
@ApplicationScoped
public class InMemoryTombstones {

  private final ConcurrentHashMap<String, Set<String>> byIndex = new ConcurrentHashMap<>();

  /** Mark every given {@code userId} as deleted for {@code indexId}. */
  public void tombstone(String indexId, Collection<String> userIds) {
    byIndex.computeIfAbsent(indexId, k -> ConcurrentHashMap.newKeySet()).addAll(userIds);
  }

  /** Snapshot of the current tombstoned ID set for {@code indexId}. */
  public Set<String> tombstonedIds(String indexId) {
    return byIndex.getOrDefault(indexId, Set.of());
  }

  /** True if {@code userId} is currently tombstoned for {@code indexId}. */
  public boolean isTombstoned(String indexId, String userId) {
    Set<String> set = byIndex.get(indexId);
    return set != null && set.contains(userId);
  }
}
