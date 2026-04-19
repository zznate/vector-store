package io.github.zznate.vectorstore.core.segment;

import io.github.jbellis.jvector.disk.RandomAccessReader;
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
   * Open the on-disk graph file ({@code graph.jvec}) for the given segment
   * as a JVector {@link RandomAccessReader}. Callers are responsible for
   * closing the reader when done.
   */
  RandomAccessReader openGraph(Segment segment) throws IOException;

  /**
   * Open a named sidecar file (e.g. {@code ordinals.jsonl},
   * {@code attributes.jsonl}, {@code tombstones.roar}, {@code header.json})
   * for the given segment. Callers are responsible for closing the stream.
   */
  InputStream openSidecar(Segment segment, String fileName) throws IOException;
}
