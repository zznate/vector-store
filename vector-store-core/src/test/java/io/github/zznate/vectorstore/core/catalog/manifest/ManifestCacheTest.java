package io.github.zznate.vectorstore.core.catalog.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.SegmentState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ManifestCacheTest {

  private static final String INDEX_ID = "demo/idx";

  private final Segment segA =
      new Segment("seg-a", INDEX_ID, SegmentState.ACTIVE, 3, 100, "p/a", Instant.EPOCH);
  private final Segment segB =
      new Segment("seg-b", INDEX_ID, SegmentState.ACTIVE, 3, 100, "p/b", Instant.EPOCH);

  @Test
  void secondActiveSegmentsCallServesFromCache() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    when(resolver.currentVersion(INDEX_ID)).thenReturn(Optional.of(1));
    when(resolver.activeSegments(INDEX_ID)).thenReturn(List.of(segA));

    ManifestCache cache = newCache(resolver, 10);

    assertThat(cache.activeSegments(INDEX_ID)).containsExactly(segA);
    assertThat(cache.activeSegments(INDEX_ID)).containsExactly(segA);

    verify(resolver, times(1)).activeSegments(INDEX_ID);
  }

  @Test
  void populateBypassesTtlForJustCommittedIndex() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    ManifestCache cache = newCache(resolver, 10);

    cache.populate(INDEX_ID, 7, List.of(segA, segB));

    // Neither call reaches the resolver — populate filled both caches.
    assertThat(cache.currentVersion(INDEX_ID)).hasValue(7);
    assertThat(cache.activeSegments(INDEX_ID)).containsExactly(segA, segB);
    verify(resolver, never()).currentVersion(INDEX_ID);
    verify(resolver, never()).activeSegments(INDEX_ID);
  }

  @Test
  void versionTtlExpiryRefetchesFromResolver() throws InterruptedException {
    ManifestResolver resolver = mock(ManifestResolver.class);
    when(resolver.currentVersion(INDEX_ID))
        .thenReturn(Optional.of(1), Optional.of(2));
    when(resolver.activeSegments(INDEX_ID))
        .thenReturn(List.of(segA))
        .thenReturn(List.of(segA, segB));

    // 5 ms TTL so the second call after Thread.sleep sees a stale entry.
    ManifestCache cache = new ManifestCache(resolver, new SimpleMeterRegistry(), 10, 5_000_000L);

    assertThat(cache.activeSegments(INDEX_ID)).containsExactly(segA);
    Thread.sleep(20);
    assertThat(cache.activeSegments(INDEX_ID)).containsExactly(segA, segB);

    verify(resolver, times(2)).currentVersion(INDEX_ID);
    verify(resolver, times(2)).activeSegments(INDEX_ID);
  }

  @Test
  void invalidateIndexWipesVersionAndTierEntriesForThatIndex() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    ManifestCache cache = newCache(resolver, 10);
    cache.populate(INDEX_ID, 1, List.of(segA));
    cache.populate("other/idx", 1, List.of());
    assertThat(cache.tier().stats().currentEntries()).isEqualTo(2);

    cache.invalidateIndex(INDEX_ID);

    assertThat(cache.tier().stats().currentEntries()).isEqualTo(1);
    when(resolver.currentVersion(INDEX_ID)).thenReturn(Optional.empty());
    assertThat(cache.currentVersion(INDEX_ID)).isEmpty();
  }

  @Test
  void invalidateAllClearsEverything() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    ManifestCache cache = newCache(resolver, 10);
    cache.populate(INDEX_ID, 1, List.of(segA));
    cache.populate("other/idx", 2, List.of(segB));

    cache.invalidateAll();

    assertThat(cache.tier().stats().currentEntries()).isZero();
  }

  @Test
  void indexWithNoVersionReturnsEmptySegmentsWithoutCachingAList() {
    ManifestResolver resolver = mock(ManifestResolver.class);
    when(resolver.currentVersion(INDEX_ID)).thenReturn(Optional.empty());
    ManifestCache cache = newCache(resolver, 10);

    assertThat(cache.activeSegments(INDEX_ID)).isEmpty();
    verify(resolver, never()).activeSegments(INDEX_ID);
  }

  private static ManifestCache newCache(ManifestResolver resolver, int maxEntries) {
    // Long TTL (1 hour) so TTL expiry doesn't fire between back-to-back
    // assertions in happy-path tests.
    return new ManifestCache(
        resolver, new SimpleMeterRegistry(), maxEntries, 3_600_000_000_000L);
  }
}
