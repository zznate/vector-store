# L2 cache stress harness

Layered stress + property + restart-cycle + soak + jcstress coverage for
the L2 cache stack — `HeapCacheTier`, `LmdbL2Provider`,
`SlabOffHeapL2Provider`, and the `ChainedL2Provider` that composes them.
The harness runs in two intensities (default unit suite vs nightly),
plus opt-in soak and jcstress profiles for deeper inspection.

## Purpose and scope

The harness verifies that the L2 stack meets its contract under
concurrency, restart, and long-running workloads. It targets four
post-redesign invariants:

- **LMDB durability** — `LmdbL2Provider` warm-restart and multi-cycle
  reopen preserve the live key set.
- **Slab destination isolation** — `SlabOffHeapL2Provider`'s
  `MemorySegment.copy` writes only to slots no longer LRU-referenced;
  a thrown copy returns the slot to the free pool.
- **Single-flight L1 promotion** — `HeapCacheTier.getOrLoad` collapses
  concurrent misses to one loader invocation per key under contention.
- **Bounded byte/slot accounting** — `currentBytes <= maxBytes` holds
  continuously for every provider (sampled during runs, asserted at
  end of run).

What the harness deliberately doesn't cover:

- Process-kill / hard-crash recovery. LMDB's copy-on-write makes the
  most acute concerns moot, but a forking process-kill harness is
  future work.
- Cross-platform validation. macOS local only until CI lands.
- Performance regression budget enforcement. Throughput numbers are
  captured; the harness doesn't gate on them across runs.

## How to run

| Invocation | What runs | Wall-clock target |
|---|---|---|
| `./mvnw -pl vector-store-core test` | Default-tagged stress + property + restart-cycle | 30 s of cache-suite time |
| `./mvnw -pl vector-store-core test -Pstress-nightly` | Default tests + `@Tag("stress-nightly")` (full intensity) | Several minutes |
| `./mvnw -pl vector-store-core test -Pstress-nightly,soak -Dl2.soak.duration=PT30M` | Above + `@Tag("soak")` | 30 minutes per provider |
| `./mvnw -pl vector-store-core verify -Pjcstress` | Default tests + jcstress smoke compile | Compile only — see § jcstress |

### Default vs nightly tagging

Surefire's default `<excludedGroups>` is `stress-nightly,soak`, so
`@Tag("stress-nightly")` and `@Tag("soak")` tests stay out of the unit
run. Profile-specific overrides:

- `-Pstress-nightly` → `<excludedGroups>soak</excludedGroups>` (only
  soak stays excluded). Also bumps `jqwik.tries.default` from 100 to
  1000 and `l2.property.maxLength` from 50 to 100.
- `-Psoak` → `<excludedGroups></excludedGroups>` (everything runs).
  Adds `-XX:NativeMemoryTracking=summary` to the test argLine so JFR
  `jdk.NativeMemoryUsage` events fire.

## What each test layer catches

| Test class | Layer | Invariant |
|---|---|---|
| `L2ProviderStressTest` | Concurrent ops × N threads | Tight-mode oracle equality, eviction-aware bounded bytes, no worker exceptions |
| `L2ProviderPropertyTest` | jqwik random sequences | Final-state correctness, `invalidateAll` empties, byte accounting, LMDB restart equivalence |
| `LmdbL2ProviderRestartCycleTest` | Cumulative open/close cycles | Disk durability across restart sequences |
| `BlockCacheConcurrencyTest` | 64 threads × pre-seeded L2 | `HeapCacheTier.getOrLoad` single-flight bound |
| `LmdbL2ProviderTest#close-during-read` | Latch-gated lifecycle race | `close()` waits for in-flight shard-lock holder; post-close `get()` throws `IllegalStateException` |
| `SlabOffHeapL2ProviderTest#close-during-read` | Latch-gated lifecycle race | Same, against the shared `Arena` |
| `L2ProviderSoakTest` | 30-minute steady state | Native memory + heap + entry counts within ≤5% of minute-5 anchor at end of run |
| `L2ScalingTest` | Read-heavy at parameterised thread counts | Per-shard contention plateau (Finding #6) |
| `jcstress/L2SmokeStressTest` | Two-actor interleaving | `getOrLoad` returns the same bytes to both actors |

## Scenario reference

`L2ProviderStressTest` parameterises six scenarios across three
provider configurations (slab, LMDB, chain).

| Scenario | Default intensity | Nightly intensity | Op mix get/put/inv | Mode |
|---|---|---|---|---|
| `read-heavy` | 4 × 5k | 16 × 100k | 90/9/1 | tight |
| `write-heavy` | 4 × 5k | 16 × 100k | 10/80/10 | tight |
| `eviction-churn` | 4 × 5k, sized per provider | 16 × 100k | 0/100/0 | eviction-aware |
| `same-key-contention` | 8 × 5k, key pool 4 | 16 × 100k, pool 4 | 30/60/10 | tight |
| `mixed-size` | 4 × 5k, payload [1, 64 KiB] | 16 × 100k | 30/60/10 | tight |
| `periodic-invalidate-all` | 4 × 5k + 1 invalidator @ 50 ms | 16 × 100k | 30/60/10 | tight |

`eviction-churn` sizes its `maxBytes` per provider:

| Provider | `maxBytes` | Working-set keys | Notes |
|---|---|---|---|
| `SlabOffHeapL2Provider` | 1 MiB | 128 | 8 shards × 64 KiB block — pushes slot pressure heavily. |
| `LmdbL2Provider` | 16 MiB | 256 | Absorbs LMDB's copy-on-write transient pressure. |
| `ChainedL2Provider` | 16 MiB per tier | 256 | Sized for the more demanding tier. |

## Findings interpretation

Three named findings have constants in the test source. The harness
fails with the embedded quantitative payload when a threshold is
exceeded.

### Finding #6 — Per-shard contention

- **Where:** `L2ScalingTest.readHeavyThroughputScalesAcrossThreads`.
- **Default measurement:** 1 thread vs 4 threads on the slab provider,
  read-heavy workload. Larger machines can ramp up via
  `-Dl2.scaling.baseline.threads=N`, `-Dl2.scaling.target.threads=N`,
  `-Dl2.scaling.multiplier=R`, `-Dl2.scaling.ops.per.thread=N`,
  `-Dl2.scaling.curve.threads=1,4,8,16`.
- **Threshold:** `THROUGHPUT_SCALING_MULTIPLIER = 1.5` — target throughput
  must be at least 1.5× the baseline.
- **LMDB note:** the curve test walks LMDB at 1/4/8/16 threads for the
  diagnostic capture, but LMDB is **not** gated on the multiplier. Its
  read scaling is dominated by MVCC coordination (mmap-page contention,
  reader-slot allocation in the env) rather than the application-level
  shard lock, so the same threshold doesn't apply.
- **Follow-up:** striped-lock granularity tuning if the slab fails.

### Finding #21 — Native memory growth

- **Where:** `L2ProviderSoakTest.steadyStateHasBoundedNativeMemoryGrowth`.
- **Measurement:** JFR `jdk.NativeMemoryUsage` recording at one-minute
  granularity across the soak run. Two anchor points compared at end:
  the minute-5 sample (post-warm-up baseline) and the end-of-run sample.
- **Threshold:** delta ≤ 5 % of the minute-5 baseline. Sample-to-sample
  monotonicity is **not** asserted — RSS / entry counts naturally
  fluctuate from JIT compilation, GC mark cycles, and eviction churn.
- **Skips:** runs shorter than 6 minutes (e.g.
  `-Dl2.soak.duration=PT2M`) skip the anchor compare via
  `assumeThat`; the JFR file still gets dumped for inspection.
- **Follow-up:** Arena lifecycle audit; LMDB env page count check.

### Finding #22 — Single-flight breakdown

- **Where:** `BlockCacheConcurrencyTest.singleFlightCollapsesConcurrentMissesAcross64Threads`.
- **Measurement:** total `vectorstore.cache.miss` count after 64
  threads × 1000 reads on 8 pre-seeded keys.
- **Threshold:** `LOADER_INVOCATION_BUDGET_PER_KEY × KEY_COUNT` (= 32
  with the defaults). Caffeine's hard single-flight gives an expected
  ratio of ~`KEY_COUNT / total_callers`; the budget leaves headroom for
  rare W-TinyLFU evict-then-readmit cycles.
- **Follow-up:** Caffeine config audit; `recordStats()` wiring check.

## Adding a new scenario

1. Implement `StressScenario` under
   `cache/stress/scenarios/MyScenarioName.java`. Provide
   `defaultConfig(kind, seed)`, `nightlyConfig(kind, seed)`, and
   `maxBytesFor(kind)`.
2. Add the scenario to `L2ProviderStressTest.scenarioByProvider()` so
   the parameterised test exercises it across the three providers.
3. If the scenario needs an auxiliary thread (e.g. periodic
   invalidate-all), set `periodicInvalidateAllInterval(...)` on the
   `StressConfig` — the harness spawns one invalidator and coordinates
   it with the worker compute paths via the harness's read-write lock.
4. Sizing rules: `maxBytes ≥ shards × blockSize` (8 × 64 KiB = 512 KiB
   minimum on the slab); tight-mode peak live bytes ≤ 50 % of
   `maxBytes` so no eviction triggers.

## Adding a jcstress scenario

The smoke test class lives at `cache/stress/jcstress/L2SmokeStressTest.java`
and is compiled only under `-Pjcstress` (the parent POM excludes
`**/cache/stress/jcstress/**` from the default test compile). Adding a
new scenario:

1. Drop a new class alongside `L2SmokeStressTest.java` annotated with
   `@JCStressTest`, `@State`, `@Outcome(...)`, and `@Actor` methods.
2. The annotation processor (jcstress 0.16) emits
   `META-INF/jcstress-tests/...` automatically on testCompile.
3. Run via the upstream-supported pattern (shaded executable jar +
   `java -jar`):

   ```
   mvn -pl vector-store-core dependency:build-classpath \
       -Pjcstress -Dmdep.outputFile=/tmp/jcstress-cp.txt -q
   java -cp $(cat /tmp/jcstress-cp.txt):vector-store-core/target/test-classes \
        org.openjdk.jcstress.Main -m sanity -t L2SmokeStressTest
   ```

   `mvn verify -Pjcstress` only verifies the class compiles and the
   annotation processor populates `META-INF/jcstress-tests/`. Wiring a
   shaded executable jar so `mvn verify -Pjcstress` runs the smoke
   end-to-end is future work.

## Known limitations

- **Process-kill / hard-crash recovery.** LMDB's copy-on-write makes the
  most acute concerns moot, but a forking process-kill harness is still
  future work. The current restart-cycle test exercises clean
  open/close/reopen cycles only.
- **Cross-platform.** Tested on macOS (arm64) only. Linux and Windows
  paths haven't been exercised.
- **Soak noise-band threshold.** The 5 % delta is hand-tuned. Long
  enough soak runs at higher tier counts may need a wider band; tune
  via the `NOISE_BAND` constant in `L2ProviderSoakTest`.
- **`L2ScalingTest` LMDB plateau.** LMDB's read scaling plateaus at
  ~1 × going 4 → 8 threads on a typical laptop because the bottleneck
  is the env's MVCC machinery, not the application-level shard lock.
  The diagnostic curve captures the numbers but doesn't gate on them.
- **jcstress execution.** `mvn verify -Pjcstress` compiles the smoke
  test class but doesn't end-to-end run jcstress (see § Adding a
  jcstress scenario).
