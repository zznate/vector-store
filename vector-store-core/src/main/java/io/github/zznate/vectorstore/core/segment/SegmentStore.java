package io.github.zznate.vectorstore.core.segment;

import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Seam between the vector-store service and whatever holds its segment
 * bytes. Phase 2 adds a local-disk implementation in
 * {@code vector-store-engine} that writes to a directory tree mirroring
 * the object-store layout from {@code docs/design-notes.md}. Phase 3
 * supplies an S3-backed implementation in {@code vector-store-storage};
 * consumers see no API change.
 */
public interface SegmentStore {

  /**
   * Move the contents of a {@link BuiltSegment}'s temp directory under the
   * given object prefix and return the canonical URI of the segment root.
   * The temp directory is consumed; callers must not use it afterwards.
   */
  URI publish(BuiltSegment local, String objectPrefix) throws IOException;

  /**
   * Return a {@link ReaderSupplier} for the segment's on-disk graph
   * ({@code graph.jvec}). Callers pass the supplier to JVector, which may
   * invoke {@link ReaderSupplier#get()} more than once internally (e.g.
   * once for the header and once per {@link
   * io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex.View}). The
   * supplier is owned by the store and cached per segment: callers must not
   * close it; the store releases every cached supplier on shutdown.
   */
  ReaderSupplier openGraph(Segment segment) throws IOException;

  /**
   * Open a named sidecar file (e.g. {@code ordinals.jsonl},
   * {@code attributes.jsonl}, {@code tombstones.roar}, {@code header.json})
   * for the given segment. Callers are responsible for closing the stream.
   */
  InputStream openSidecar(Segment segment, String fileName) throws IOException;

  /**
   * Replace a named sidecar file with the given bytes. The graph file is
   * strictly immutable after commit; mutable sidecars (e.g., the
   * tombstone bitmap) use this path. Implementations write atomically
   * when feasible, but concurrent commits for the same segment are the
   * caller's responsibility to serialise.
   */
  void putSidecar(Segment segment, String fileName, byte[] content) throws IOException;

  /**
   * Recursively remove every object whose key starts with {@code
   * objectPrefix}. Used by the retention sweep to drop a segment's
   * graph + sidecars when its parent index hard-deletes.
   *
   * <p>Idempotent: deleting a prefix that does not exist is a no-op,
   * not an error. Implementations may release any cached graph mappings
   * for segments under the prefix before removing files; callers must
   * not call this concurrently with active queries against the same
   * segment id.
   */
  void deletePrefix(String objectPrefix) throws IOException;
}
