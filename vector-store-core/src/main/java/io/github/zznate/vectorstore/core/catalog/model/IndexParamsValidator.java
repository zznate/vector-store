package io.github.zznate.vectorstore.core.catalog.model;

import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java logic for the startup catalog compatibility check. Walks
 * every persisted {@link VectorIndex}, parses its {@code engineParams}
 * JSON via {@link IndexBuildParams#fromJson(String)}, and compares the
 * parsed value against the current per-process defaults.
 *
 * <p>Two distinct outputs:
 * <ul>
 *   <li>{@link Drift} entries — indexes whose persisted params differ
 *       from current globals on a JVector-mid-life-incompatible knob
 *       ({@code m}, {@code addHierarchy}). Per JVector PR #659's
 *       {@code validateGraphConfiguration}, segments within one
 *       compaction must agree on these. Drift is normal whenever
 *       globals have moved since an index was created — surfaced at
 *       INFO so operators can see it; never fatal.
 *   <li>{@link ParseFailure} entries — indexes whose persisted JSON
 *       fails to parse or fails the {@link IndexBuildParams}
 *       constructor invariants. These signal catalog corruption or a
 *       pre-validation-era row; the caller decides whether to log or
 *       throw based on {@link StartupValidationMode}.
 * </ul>
 *
 * <p>Stateless, no framework hooks; the boot wiring lives in the app
 * module so this class stays unit-testable with a {@link
 * VectorIndexRepository} stub and a plain {@link IndexBuildParamsDefaults}
 * record implementation.
 */
public final class IndexParamsValidator {

  private final VectorIndexRepository repository;
  private final IndexBuildParamsDefaults defaults;

  public IndexParamsValidator(
      VectorIndexRepository repository, IndexBuildParamsDefaults defaults) {
    this.repository = repository;
    this.defaults = defaults;
  }

  /** Run the check across every persisted index. */
  public ValidationReport validate() {
    IndexBuildParams currentDefaults = IndexBuildParams.defaults(defaults);
    List<Drift> drift = new ArrayList<>();
    List<ParseFailure> failures = new ArrayList<>();
    for (VectorIndex idx : repository.listAll()) {
      classify(idx, currentDefaults, drift, failures);
    }
    return new ValidationReport(
        currentDefaults, Collections.unmodifiableList(drift), Collections.unmodifiableList(failures));
  }

  private static void classify(
      VectorIndex idx,
      IndexBuildParams currentDefaults,
      List<Drift> drift,
      List<ParseFailure> failures) {
    IndexBuildParams persisted;
    try {
      persisted = IndexBuildParams.fromJson(idx.engineParams());
    } catch (RuntimeException e) {
      failures.add(new ParseFailure(idx.indexId(), e.getMessage()));
      return;
    }
    if (driftsFromDefaults(persisted, currentDefaults)) {
      drift.add(new Drift(idx.indexId(), persisted, currentDefaults));
    }
  }

  /**
   * True when {@code persisted} differs from {@code currentDefaults} on
   * a knob JVector requires segments-in-one-compaction to share. Other
   * knobs ({@code beamWidth}, {@code alpha}, {@code neighborOverflow})
   * may drift without preventing compaction; they are not flagged.
   */
  private static boolean driftsFromDefaults(
      IndexBuildParams persisted, IndexBuildParams currentDefaults) {
    return persisted.m() != currentDefaults.m()
        || persisted.addHierarchy() != currentDefaults.addHierarchy();
  }

  /** Result of one {@link #validate()} call. */
  public record ValidationReport(
      IndexBuildParams currentDefaults, List<Drift> drift, List<ParseFailure> failures) {}

  /** An index whose persisted params disagree with current globals on a load-bearing knob. */
  public record Drift(String indexId, IndexBuildParams persisted, IndexBuildParams currentDefaults) {}

  /** An index whose persisted {@code engine_params} JSON could not be parsed or validated. */
  public record ParseFailure(String indexId, String message) {}
}
