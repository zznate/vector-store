package io.github.zznate.vectorstore.app.startup;

import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParamsDefaults;
import io.github.zznate.vectorstore.core.catalog.model.IndexConfig;
import io.github.zznate.vectorstore.core.catalog.model.IndexParamsValidator;
import io.github.zznate.vectorstore.core.catalog.model.StartupValidationMode;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boot-time bridge between the pure-Java {@link IndexParamsValidator}
 * and the Quarkus runtime. Runs once per JVM when the
 * {@link StartupEvent} fires; behaviour is gated by
 * {@link IndexConfig#startupValidation()}.
 *
 * <p>Drift entries (persisted-vs-current divergence on a JVector
 * mid-life-incompatible knob) are always logged at INFO when present —
 * drift is normal once globals move, but operators want to see the
 * paper trail. {@link
 * io.github.zznate.vectorstore.core.catalog.model.IndexParamsValidator.ParseFailure}
 * entries are logged at WARN, or escalated to a startup failure under
 * {@link StartupValidationMode#ERROR}.
 */
@ApplicationScoped
public class IndexParamsStartupHook {

  private static final Logger LOG = LoggerFactory.getLogger(IndexParamsStartupHook.class);

  private final VectorIndexRepository indexes;
  private final IndexBuildParamsDefaults defaults;
  private final IndexConfig indexConfig;

  @Inject
  public IndexParamsStartupHook(
      VectorIndexRepository indexes,
      IndexBuildParamsDefaults defaults,
      IndexConfig indexConfig) {
    this.indexes = indexes;
    this.defaults = defaults;
    this.indexConfig = indexConfig;
  }

  void onStart(@Observes StartupEvent event) {
    StartupValidationMode mode = indexConfig.startupValidation();
    if (mode == StartupValidationMode.OFF) {
      return;
    }

    IndexParamsValidator.ValidationReport report =
        new IndexParamsValidator(indexes, defaults).validate();

    logDriftIfAny(report);
    handleFailures(report, mode);
  }

  private static void logDriftIfAny(IndexParamsValidator.ValidationReport report) {
    if (report.drift().isEmpty() || !LOG.isInfoEnabled()) {
      return;
    }
    LOG.info(
        "{} index(es) have engine_params drifting from current globals on a"
            + " JVector mid-life-incompatible knob (m / addHierarchy);"
            + " existing indexes keep their persisted params, no action required:",
        report.drift().size());
    for (IndexParamsValidator.Drift d : report.drift()) {
      LOG.info(
          "  {}: persisted (m={}, addHierarchy={}) globals (m={}, addHierarchy={})",
          d.indexId(),
          d.persisted().m(),
          d.persisted().addHierarchy(),
          d.currentDefaults().m(),
          d.currentDefaults().addHierarchy());
    }
  }

  private static void handleFailures(
      IndexParamsValidator.ValidationReport report, StartupValidationMode mode) {
    if (report.failures().isEmpty()) {
      return;
    }
    if (mode == StartupValidationMode.ERROR) {
      StringBuilder msg =
          new StringBuilder("startup validation failed for ")
              .append(report.failures().size())
              .append(" index(es):");
      for (IndexParamsValidator.ParseFailure f : report.failures()) {
        msg.append("\n  ").append(f.indexId()).append(": ").append(f.message());
      }
      throw new IllegalStateException(msg.toString());
    }
    if (LOG.isWarnEnabled()) {
      LOG.warn(
          "{} index(es) failed engine_params validation; service continues under"
              + " startup-validation=warn (set startup-validation=error to refuse"
              + " startup, =off to skip):",
          report.failures().size());
      for (IndexParamsValidator.ParseFailure f : report.failures()) {
        LOG.warn("  {}: {}", f.indexId(), f.message());
      }
    }
  }
}
