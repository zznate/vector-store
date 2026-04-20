package io.github.zznate.vectorstore.engine.tombstone;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-index in-memory staging set of tombstoned user IDs. Delete requests
 * land here; {@link CommitCoordinator} drains the set into per-segment
 * {@code tombstones.roar} sidecars on the next commit. Queries read this
 * set alongside the persisted bitmaps so a delete is visible immediately,
 * before the next commit.
 *
 * <p>Uncommitted staged deletes are lost on process restart. A WAL or
 * catalog-backed staging durability story is a phase-2 concern.
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

  /**
   * Snapshot the staged set for {@code indexId} without mutating it. The
   * commit coordinator persists the snapshot, then calls {@link
   * #removeAll} with the same snapshot once persistence succeeds. If
   * commit fails, the staged set is untouched and the next commit retries
   * the same IDs.
   */
  public Set<String> snapshot(String indexId) {
    Set<String> staged = byIndex.get(indexId);
    return staged == null ? Set.of() : Set.copyOf(staged);
  }

  /**
   * Remove the given user IDs from the staged set for {@code indexId}.
   * IDs added to staging during the surrounding commit stay for the next
   * commit.
   */
  public void removeAll(String indexId, Set<String> userIds) {
    Set<String> staged = byIndex.get(indexId);
    if (staged == null || userIds.isEmpty()) {
      return;
    }
    staged.removeAll(userIds);
  }
}
