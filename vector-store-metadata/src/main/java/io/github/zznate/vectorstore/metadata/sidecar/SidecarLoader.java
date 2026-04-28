package io.github.zznate.vectorstore.metadata.sidecar;

import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.segment.SegmentStore;
import io.github.zznate.vectorstore.metadata.posting.PostingListReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Facade over {@link SidecarCache} + {@link SegmentStore} that returns
 * parsed sidecars for a given segment, loading on miss. Separating this
 * from the cache itself lets the caller stay unaware of where the bytes
 * come from.
 *
 * <p>Both {@link AttributeSidecar} and {@link TombstoneSidecar} are keyed
 * per segment; the cache's byte-weighted eviction serves both kinds from
 * the same LRU budget so the process-wide heap footprint stays bounded.
 *
 * <p>Loading is intentionally eager (blocking read of the sidecar bytes)
 * rather than lazy-per-access: queries that touch a sidecar typically
 * touch all of it, so streaming buys nothing and complicates the cache
 * semantics.
 */
@ApplicationScoped
public class SidecarLoader {

  private final SidecarCache cache;
  private final SegmentStore segmentStore;

  @Inject
  public SidecarLoader(SidecarCache cache, SegmentStore segmentStore) {
    this.cache = cache;
    this.segmentStore = segmentStore;
  }

  public AttributeSidecar attributes(Segment segment) {
    String key = SidecarCache.attributesKey(segment.segmentId());
    CachedSidecar cached = cache.getIfPresent(key);
    if (cached instanceof AttributeSidecar attrs) {
      return attrs;
    }
    try (InputStream in = segmentStore.openSidecar(segment, "attributes.jsonl")) {
      AttributeSidecar loaded = AttributeSidecar.parse(in);
      cache.put(key, loaded);
      return loaded;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load attributes.jsonl for segment " + segment.segmentId(), e);
    }
  }

  public TombstoneSidecar tombstones(Segment segment) {
    String key = SidecarCache.tombstonesKey(segment.segmentId());
    CachedSidecar cached = cache.getIfPresent(key);
    if (cached instanceof TombstoneSidecar tomb) {
      return tomb;
    }
    try (InputStream in = segmentStore.openSidecar(segment, "tombstones.roar")) {
      TombstoneSidecar loaded = TombstoneSidecar.read(in);
      cache.put(key, loaded);
      return loaded;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load tombstones.roar for segment " + segment.segmentId(), e);
    }
  }

  public PostingListReader postings(Segment segment) {
    String key = SidecarCache.postingsKey(segment.segmentId());
    CachedSidecar cached = cache.getIfPresent(key);
    if (cached instanceof PostingListReader reader) {
      return reader;
    }
    try (InputStream in = segmentStore.openSidecar(segment, "postings.bin")) {
      PostingListReader loaded = PostingListReader.read(in);
      cache.put(key, loaded);
      return loaded;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load postings.bin for segment " + segment.segmentId(), e);
    }
  }

  /** Drop any cached sidecars for this segment. Used by the commit path
   * after it re-uploads {@code tombstones.roar} so the next query loads
   * the fresh bytes. */
  public void invalidate(Segment segment) {
    cache.invalidate(SidecarCache.attributesKey(segment.segmentId()));
    cache.invalidate(SidecarCache.tombstonesKey(segment.segmentId()));
    cache.invalidate(SidecarCache.postingsKey(segment.segmentId()));
  }
}
