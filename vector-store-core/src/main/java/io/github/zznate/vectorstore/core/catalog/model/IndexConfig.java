package io.github.zznate.vectorstore.core.catalog.model;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Process-level knobs scoped to the index subsystem that are not
 * default values. {@link IndexBuildParamsDefaults} sits next to this
 * under {@code vectorstore.index.defaults.*} and owns the per-field
 * defaults; this mapping owns behaviour that operates on already-
 * persisted indexes.
 *
 * <p>Today the only such knob is {@link #startupValidation()} — see
 * {@link IndexParamsValidator} for what it gates.
 */
@ConfigMapping(prefix = "vectorstore.index")
public interface IndexConfig {

  /** Startup-time behaviour when persisted index params diverge from current globals. */
  @WithName("startup-validation")
  @WithDefault("warn")
  StartupValidationMode startupValidation();
}
