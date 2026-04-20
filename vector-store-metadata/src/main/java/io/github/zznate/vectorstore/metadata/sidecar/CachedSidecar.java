package io.github.zznate.vectorstore.metadata.sidecar;

/**
 * Marker for objects that live in the process-wide {@link SidecarCache}.
 * The approximate on-heap size drives byte-weighted eviction; there is no
 * contract beyond "bigger than zero and stable for the lifetime of the
 * entry".
 */
public interface CachedSidecar {

  /**
   * Rough heap footprint in bytes. Implementations pick a sensible
   * approximation — string bytes for attribute sidecars, serialized-form
   * bytes for tombstone bitmaps — and do not need to be exact.
   */
  long sizeBytes();
}
