package io.github.zznate.vectorstore.core.cache;

/**
 * Per-index cache policy. Controls how the warm-query tier treats segments
 * belonging to a given index.
 *
 * <ul>
 *   <li>{@link #RESIDENT} — every ACTIVE segment is pinned in the segment-handle
 *       cache and excluded from LRU eviction. Block bytes still flow through L1
 *       and (when enabled) L2. Use for indexes with the strictest latency floor.
 *   <li>{@link #SMART} — default. Segments share the LRU budget; block bytes
 *       use L1 with optional L2.
 *   <li>{@link #MINIMAL} — L1 only. The block cache skips L2 lookups and
 *       writes for this index's segments. Use for cost-sensitive cold indexes
 *       where the warm-tier overhead is not justified.
 * </ul>
 */
public enum CachePolicy {
  RESIDENT,
  SMART,
  MINIMAL;

  /** Default policy applied when an index does not specify one. */
  public static CachePolicy defaultPolicy() {
    return SMART;
  }
}
