package io.github.zznate.vectorstore.app.retention;

import io.github.zznate.vectorstore.core.retention.RetentionConfig;
import io.github.zznate.vectorstore.core.retention.RetentionSweep;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus binding for the retention sweep. Fires every
 * {@code vectorstore.retention.interval} (15 minutes by default), but
 * does nothing unless {@code vectorstore.retention.enabled=true}.
 *
 * <p>Disabled by default so a fresh deployment never silently
 * hard-deletes data — operators must opt in explicitly. The scheduler
 * binding is wafer-thin so the cascade logic in {@link RetentionSweep}
 * stays unit-testable without the CDI container or a real scheduler.
 */
@ApplicationScoped
public class RetentionSweepScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionSweepScheduler.class);

  private final RetentionSweep sweep;
  private final RetentionConfig config;

  @Inject
  public RetentionSweepScheduler(RetentionSweep sweep, RetentionConfig config) {
    this.sweep = sweep;
    this.config = config;
  }

  @Scheduled(every = "{vectorstore.retention.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    if (!config.enabled()) {
      return;
    }
    try {
      sweep.runOnce();
    } catch (RuntimeException e) {
      // The per-resource catch blocks inside RetentionSweep already
      // log + continue; this is the last-resort guard so a thrown
      // sweep does not poison the scheduler thread for future ticks.
      if (LOG.isErrorEnabled()) {
        LOG.error("retention sweep iteration failed", e);
      }
    }
  }
}
