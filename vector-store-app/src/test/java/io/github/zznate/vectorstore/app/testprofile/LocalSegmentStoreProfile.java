package io.github.zznate.vectorstore.app.testprofile;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Forces {@code vectorstore.segments.store=local} so a {@link
 * io.quarkus.test.junit.QuarkusTest @QuarkusTest} exercises the phase-2
 * local-filesystem {@code SegmentStore} instead of standing up MinIO. Used
 * for tests that care about the put / commit / query path end-to-end but
 * don't specifically verify object-store fidelity. Phase-3 MinIO-specific
 * behaviour (uploads, block cache, commit resilience) lives in dedicated
 * integration tests that bring up a Testcontainer MinIO instead.
 */
public final class LocalSegmentStoreProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("vectorstore.segments.store", "local");
  }
}
