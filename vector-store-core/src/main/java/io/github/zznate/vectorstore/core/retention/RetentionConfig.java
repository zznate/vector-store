package io.github.zznate.vectorstore.core.retention;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;

/**
 * Process-level knobs for the retention sweep. Bound to
 * {@code vectorstore.retention.*} in {@code application.properties} (see
 * the same file for human-readable comments on each property).
 *
 * <p>Disabled by default so a fresh deployment never silently
 * hard-deletes data. Operators must opt in explicitly. The defaults
 * encoded here mirror the {@code application.properties} comments — keep
 * the two in sync when adjusting either.
 */
@ConfigMapping(prefix = "vectorstore.retention")
public interface RetentionConfig {

  /**
   * Master switch for the retention sweep. When {@code false} the sweep
   * timer never fires and soft-deleted rows accumulate indefinitely;
   * intended for staging environments and during the initial rollout.
   */
  @WithDefault("false")
  boolean enabled();

  /** How long the sweep waits between iterations once enabled. */
  @WithName("interval")
  @WithDefault("PT15M")
  Duration interval();

  /** Per-resource retention windows. */
  IndexRetention index();

  BucketRetention bucket();

  interface IndexRetention {
    /** How long an index stays soft-deleted before the sweep hard-deletes it. */
    @WithName("window")
    @WithDefault("P7D")
    Duration window();
  }

  interface BucketRetention {
    /**
     * How long a bucket stays soft-deleted before it becomes eligible
     * for hard-delete. The sweep additionally requires every child
     * index to be hard-deleted (any state) before tearing down the
     * bucket row, so the effective bucket lifetime is at least
     * {@code max(bucket.window, index.window)}.
     */
    @WithName("window")
    @WithDefault("P7D")
    Duration window();
  }
}
