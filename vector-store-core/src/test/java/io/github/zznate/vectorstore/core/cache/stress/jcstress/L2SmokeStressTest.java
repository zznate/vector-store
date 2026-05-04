package io.github.zznate.vectorstore.core.cache.stress.jcstress;

import io.github.zznate.vectorstore.core.cache.HeapCacheTier;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * jcstress smoke for {@link HeapCacheTier#getOrLoad}: two actors race
 * a {@code getOrLoad} on the same key with a loader that emits a
 * fixed byte sequence. Caffeine's load-via-{@code cache.get(K, Function)}
 * is single-flight under contention but a non-overlapping sequence
 * (actor1 finishes before actor2 starts) can run the loader twice.
 *
 * <p>Result encoding (III_Result):
 * <ul>
 *   <li>r1 — first byte observed by actor1</li>
 *   <li>r2 — first byte observed by actor2</li>
 *   <li>r3 — total loader invocations across both actors (1 or 2)</li>
 * </ul>
 *
 * <p>Acceptable: r1 == r2 (both actors saw the same value) AND
 * r3 ∈ {1, 2}. Forbidden (default expectation for unmatched outcomes):
 * r1 != r2 — bytes diverged for the same key.
 *
 * <p>Compiled only under {@code -Pjcstress} (the parent POM's default
 * compiler config excludes this directory; the profile overrides). Run
 * via {@code ./mvnw -pl vector-store-core verify -Pjcstress}.
 */
@JCStressTest
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE,
    desc = "single-flight: one loader invocation, both actors observe the same byte")
@Outcome(id = "1, 1, 2", expect = Expect.ACCEPTABLE,
    desc = "non-overlapping: two loader invocations, both actors still observe the same byte")
@State
public class L2SmokeStressTest {

  private static final byte LOADER_BYTE = 1;

  private final HeapCacheTier<String, byte[]> tier;
  private final AtomicInteger loaderInvocations = new AtomicInteger();

  public L2SmokeStressTest() {
    this.tier =
        HeapCacheTier.<String, byte[]>builder("jcstress")
            .byteWeighted(1L << 20, v -> v.length)
            .build();
  }

  @Actor
  public void actor1(III_Result r) {
    byte[] value = tier.getOrLoad("k", k -> {
      loaderInvocations.incrementAndGet();
      return new byte[] {LOADER_BYTE};
    });
    r.r1 = value[0];
  }

  @Actor
  public void actor2(III_Result r) {
    byte[] value = tier.getOrLoad("k", k -> {
      loaderInvocations.incrementAndGet();
      return new byte[] {LOADER_BYTE};
    });
    r.r2 = value[0];
  }

  @Arbiter
  public void recordLoaderInvocations(III_Result r) {
    r.r3 = loaderInvocations.get();
  }
}
