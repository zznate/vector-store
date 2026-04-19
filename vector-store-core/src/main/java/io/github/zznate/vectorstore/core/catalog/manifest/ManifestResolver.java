package io.github.zznate.vectorstore.core.catalog.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zznate.vectorstore.core.catalog.model.ManifestVersion;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.repository.ManifestVersionRepository;
import io.github.zznate.vectorstore.core.catalog.repository.SegmentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Resolves an index's current manifest — the ordered list of
 * {@link Segment} rows that make up its active state at this instant.
 *
 * <p>Reads the highest-versioned {@code manifest_version} row on every
 * call and materialises each referenced segment with one {@code findById}
 * per entry. The query path goes through this resolver so adding caching
 * later is a single-class change.
 *
 * <p>TODO(later: cache): when the query path gets hot, back this with an
 * in-process LRU keyed by {@code (indexId, version)}. Invalidation is
 * simple because manifest rows are append-only — a newer version for an
 * index evicts its older entries. Do not add before measurements justify
 * it.
 */
@ApplicationScoped
public class ManifestResolver {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final ManifestVersionRepository manifests;
  private final SegmentRepository segments;

  @Inject
  public ManifestResolver(ManifestVersionRepository manifests, SegmentRepository segments) {
    this.manifests = manifests;
    this.segments = segments;
  }

  /**
   * The ordered active segment list for {@code indexId}, or an empty list
   * if the index has no committed manifests yet.
   */
  public List<Segment> activeSegments(String indexId) {
    return manifests
        .findCurrent(indexId)
        .map(this::resolveSegments)
        .orElse(Collections.emptyList());
  }

  /** The current manifest version number, or empty if none exists yet. */
  public Optional<Integer> currentVersion(String indexId) {
    return manifests.findCurrent(indexId).map(ManifestVersion::version);
  }

  private List<Segment> resolveSegments(ManifestVersion manifest) {
    String[] segmentIds;
    try {
      segmentIds = OBJECT_MAPPER.readValue(manifest.segmentIds(), String[].class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Malformed segment_ids JSON in manifest_version (index=%s version=%d)"
              .formatted(manifest.indexId(), manifest.version()),
          e);
    }
    return java.util.Arrays.stream(segmentIds)
        .map(segments::findById)
        .flatMap(Optional::stream)
        .toList();
  }
}
