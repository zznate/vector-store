package io.github.zznate.vectorstore.core.catalog.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Tunable JVector parameters for a given index. Stored as JSON in the
 * {@code vector_index.engine_params} column and consumed by the engine's
 * segment builder at commit time.
 *
 * <p>Defaults come from {@code docs/design-notes.md}. Clients override at
 * index-creation time via the {@code engineParams} field on
 * {@code CreateIndexRequest}; any missing field falls back to the default
 * and the canonical merged form is persisted so later reads never need to
 * re-apply defaults.
 *
 * <h2>Parameter guidance</h2>
 *
 * All parameters trade recall against build cost and/or memory. Start with
 * defaults and adjust only if a measurement warrants it.
 *
 * <ul>
 *   <li><b>{@code m}</b> — graph degree (connections per node). Higher →
 *       better recall at query time, more memory per node, slightly slower
 *       build. Typical range 16–64. JVector default 32.
 *   <li><b>{@code beamWidth}</b> — candidate pool size during graph
 *       construction (the Vamana equivalent of HNSW's {@code ef_construction}).
 *       Higher → better recall, slower build. Typical range 100–500. 200
 *       matches Phase 1's cold-archive profile: build once, query many.
 *   <li><b>{@code neighborOverflow}</b> — multiplier applied to the
 *       neighbour pool during insertion. Larger values let the graph
 *       settle into better neighbourhoods at the cost of build time.
 *       JVector default 1.2.
 *   <li><b>{@code alpha}</b> — pruning threshold from the Vamana paper.
 *       Higher → more long-range edges retained; lower → more aggressive
 *       pruning and a smaller index. JVector default 1.2.
 *   <li><b>{@code pqSubspaces}</b> — number of subspaces for product
 *       quantisation. Must evenly divide the vector dimension. For 1024-d
 *       vectors, 128 subspaces gives 8 dimensions per subspace — the
 *       JVector-idiomatic setting.
 *   <li><b>{@code pqSubspaceClusters}</b> — cluster count per PQ subspace.
 *       Held at 256 for now (one byte per subspace index).
 *   <li><b>{@code addHierarchy}</b> — {@code false} builds a flat
 *       Vamana / DiskANN-style graph; {@code true} builds a multi-layer
 *       HNSW-style hierarchical graph. On-disk format is compatible either
 *       way, so flipping per-index is a pure build-time choice. Defaults
 *       to {@code false}; set to {@code true} if an index's query profile
 *       favours the HNSW entry-point amortisation.
 * </ul>
 */
public record IndexBuildParams(
    int m,
    int beamWidth,
    float neighborOverflow,
    float alpha,
    int pqSubspaces,
    int pqSubspaceClusters,
    boolean addHierarchy) {

  public IndexBuildParams {
    if (m < 1) {
      throw new IllegalArgumentException("m must be >= 1, got " + m);
    }
    if (beamWidth < 1) {
      throw new IllegalArgumentException("beamWidth must be >= 1, got " + beamWidth);
    }
    if (neighborOverflow < 1.0f) {
      throw new IllegalArgumentException(
          "neighborOverflow must be >= 1.0, got " + neighborOverflow);
    }
    if (alpha <= 0.0f) {
      throw new IllegalArgumentException("alpha must be > 0.0, got " + alpha);
    }
    if (pqSubspaces < 1) {
      throw new IllegalArgumentException("pqSubspaces must be >= 1, got " + pqSubspaces);
    }
    if (pqSubspaceClusters < 2 || pqSubspaceClusters > 256) {
      throw new IllegalArgumentException(
          "pqSubspaceClusters must be in [2, 256], got " + pqSubspaceClusters);
    }
  }

  /**
   * Defaults from {@code docs/design-notes.md}: {@code M=32},
   * {@code beamWidth=200}, {@code neighborOverflow=1.2}, {@code alpha=1.2},
   * {@code pqSubspaces=128}, {@code pqSubspaceClusters=256},
   * {@code addHierarchy=false}.
   */
  public static IndexBuildParams defaults() {
    return new IndexBuildParams(32, 200, 1.2f, 1.2f, 128, 256, false);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /** Canonical JSON form stored in {@code vector_index.engine_params}. */
  public String toJson() {
    try {
      return MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unable to serialise IndexBuildParams", e);
    }
  }

  /** Inverse of {@link #toJson()}. Returns defaults for null / blank input. */
  public static IndexBuildParams fromJson(String json) {
    if (json == null || json.isBlank()) {
      return defaults();
    }
    try {
      return MAPPER.readValue(json, IndexBuildParams.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unable to parse IndexBuildParams JSON: " + json, e);
    }
  }

  /**
   * Build an instance by merging the caller's {@code overrides} map over
   * {@link #defaults()}. Unknown keys are ignored. {@code null} and empty
   * maps return defaults verbatim.
   */
  public static IndexBuildParams fromOverrides(Map<String, Object> overrides) {
    Map<String, Object> merged = MAPPER.convertValue(defaults(), MAP_TYPE);
    if (overrides != null && !overrides.isEmpty()) {
      merged.putAll(overrides);
    }
    return MAPPER.convertValue(merged, IndexBuildParams.class);
  }

  /** Projection back to a {@code Map<String,Object>} for REST responses. */
  public Map<String, Object> toMap() {
    return MAPPER.convertValue(this, MAP_TYPE);
  }
}
