package io.github.zznate.vectorstore.engine.store;

import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.segment.BuiltSegment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SegmentStore} backed by a local directory tree that mirrors the
 * S3 object-store layout from {@code docs/design-notes.md}. The Phase 2
 * implementation used end-to-end in dev and tests; Phase 3 swaps the
 * equivalent S3 implementation without consumer changes.
 *
 * <p>Graph files are memory-mapped once per segment and the
 * {@link ReaderSupplier} is cached so concurrent queries against the same
 * segment share the mapping. {@link #openGraph} returns the cached supplier
 * directly; callers must not close it (JVector's {@code OnDiskGraphIndex}
 * pulls fresh per-view readers via {@code supplier.get()}). Mappings are
 * released at {@link #close()}.
 */
public class LocalSegmentStore implements SegmentStore, AutoCloseable {

  private final Path root;
  private final ConcurrentHashMap<String, ReaderSupplier> graphSuppliers = new ConcurrentHashMap<>();

  public LocalSegmentStore(Path root) {
    this.root = root;
  }

  @Override
  public URI publish(BuiltSegment local, String objectPrefix) throws IOException {
    Path dest = root.resolve(objectPrefix).toAbsolutePath();
    Files.createDirectories(dest);
    try (var stream = Files.list(local.tempDirectory())) {
      List<Path> sources = stream.toList();
      for (Path src : sources) {
        Files.move(src, dest.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.deleteIfExists(local.tempDirectory());
    return dest.toUri();
  }

  @Override
  public ReaderSupplier openGraph(Segment segment) {
    return graphSuppliers.computeIfAbsent(segment.segmentId(), id -> createSupplier(segment));
  }

  @Override
  public InputStream openSidecar(Segment segment, String fileName) throws IOException {
    Path path = root.resolve(segment.objectPrefix()).resolve(fileName);
    return Files.newInputStream(path);
  }

  /**
   * Release every cached graph mapping. Called by the app module on CDI
   * disposal so the process exits cleanly.
   */
  @Override
  public void close() {
    for (ReaderSupplier supplier : graphSuppliers.values()) {
      try {
        supplier.close();
      } catch (IOException ignore) {
        // Best-effort on shutdown.
      }
    }
    graphSuppliers.clear();
  }

  private ReaderSupplier createSupplier(Segment segment) {
    Path path = root.resolve(segment.objectPrefix()).resolve("graph.jvec");
    try {
      return new SimpleMappedReader.Supplier(path);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to memory-map graph.jvec for segment " + segment.segmentId(), e);
    }
  }
}
