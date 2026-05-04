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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SegmentStore} backed by a local directory tree that mirrors the
 * S3 object-store layout from {@code docs/design-notes.md}. Used
 * end-to-end in dev and tests; the equivalent S3 implementation in
 * {@code vector-store-storage} satisfies the same interface for
 * production deployments without consumer changes.
 *
 * <p>Graph files are memory-mapped once per segment and the
 * {@link ReaderSupplier} is cached so concurrent queries against the same
 * segment share the mapping. {@link #openGraph} returns the cached supplier
 * directly; callers must not close it (JVector's {@code OnDiskGraphIndex}
 * pulls fresh per-view readers via {@code supplier.get()}). Mappings are
 * released at {@link #close()}.
 */
public class LocalSegmentStore implements SegmentStore, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(LocalSegmentStore.class);

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

  @Override
  public void putSidecar(Segment segment, String fileName, byte[] content) throws IOException {
    Path dir = root.resolve(segment.objectPrefix());
    Files.createDirectories(dir);
    Path tmp = dir.resolve(fileName + ".tmp");
    Files.write(tmp, content);
    Files.move(tmp, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public void deletePrefix(String objectPrefix) throws IOException {
    Path dir = root.resolve(objectPrefix).toAbsolutePath();
    if (!Files.exists(dir)) {
      return;
    }
    // Drop any cached graph supplier whose path lives under this prefix.
    // Without this, the memory-mapped reader would keep the underlying
    // files open on Windows and miscount on Linux post-delete.
    graphSuppliers
        .entrySet()
        .removeIf(
            entry -> {
              try {
                Path graphPath = dir.resolve("graph.jvec");
                if (Files.exists(graphPath) && entry.getValue() != null) {
                  entry.getValue().close();
                  return true;
                }
              } catch (IOException e) {
                if (LOG.isWarnEnabled()) {
                  LOG.warn(
                      "failed to close graph supplier {} during deletePrefix({})",
                      entry.getKey(),
                      objectPrefix,
                      e);
                }
              }
              return false;
            });

    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(LocalSegmentStore::deleteQuietly);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to delete " + path, e);
    }
  }

  /**
   * Release every cached graph mapping. Called by the app module on CDI
   * disposal so the process exits cleanly.
   */
  @Override
  public void close() {
    for (var entry : graphSuppliers.entrySet()) {
      try {
        entry.getValue().close();
      } catch (IOException e) {
        // Best-effort on shutdown — we still want the close loop to
        // continue across remaining suppliers, but operators need the
        // stack trace to triage a leaked mapping.
        if (LOG.isWarnEnabled()) {
          LOG.warn("failed to close graph supplier for segment {}", entry.getKey(), e);
        }
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
