package io.github.zznate.vectorstore.core.catalog.model;

/**
 * Startup-time behaviour when {@link IndexParamsValidator} finds a
 * persisted-versus-current divergence in any index's engine parameters.
 *
 * <ul>
 *   <li>{@link #OFF} — skip the check entirely. Fastest boot.
 *   <li>{@link #WARN} — log issues, continue. Default. Drift entries are
 *       always logged at INFO regardless of mode (drift is normal — it
 *       is the operator's signal that globals have moved since an
 *       index was created).
 *   <li>{@link #ERROR} — refuse to start if any index's persisted JSON
 *       fails to parse or fails the {@link IndexBuildParams}
 *       constructor invariants. Use in production where a corrupt
 *       catalog row should fail loudly rather than serve traffic.
 * </ul>
 *
 * <p>Configured at {@code vectorstore.index.startup-validation}; see
 * {@code IndexConfig}.
 */
public enum StartupValidationMode {
  OFF,
  WARN,
  ERROR
}
