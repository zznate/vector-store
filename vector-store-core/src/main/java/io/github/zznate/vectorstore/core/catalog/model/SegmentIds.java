package io.github.zznate.vectorstore.core.catalog.model;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Segment ID generator. UUIDv7 (RFC 9562) — 48-bit unix-ms timestamp prefix
 * + random suffix — so lexical order matches creation order.
 *
 * <p>Chosen over UUIDv4 because:
 *
 * <ul>
 *   <li>Listing segments under an index (in the catalog or on the object
 *       store) returns them in creation order without an explicit
 *       {@code ORDER BY created_at}.
 *   <li>Later-stage compaction picks the oldest N segments as
 *       {@code ORDER BY segment_id LIMIT N}, which maps to a sequential
 *       B-tree / object-store range scan.
 *   <li>Segment IDs are internal identifiers, not security tokens; the
 *       slight reduction in entropy vs UUIDv4 is irrelevant here.
 * </ul>
 */
public final class SegmentIds {

  private SegmentIds() {}

  /** Generate a fresh UUIDv7 segment identifier. */
  public static String newSegmentId() {
    return UuidCreator.getTimeOrderedEpoch().toString();
  }
}
