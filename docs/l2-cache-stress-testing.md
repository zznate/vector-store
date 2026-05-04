# L2 cache stress-testing plan

> **Updated post-redesign.** The original 2026-04 plan targeted the
> custom `LocalDiskL2Provider` (mmap + sidecar + bump pointer) and the
> per-entry-Arena `OffHeapArenaL2Provider`. Both are gone — the disk
> tier now lives behind LMDB (`LmdbL2Provider`) and the off-heap tier
> uses a slab allocator with one shared Arena (`SlabOffHeapL2Provider`).
> Hazards specific to the deleted designs are struck through.
>
> The current harness lives at
> `vector-store-core/src/test/java/.../cache/stress/`; see its
> `README.md` for the operator-facing run instructions.

## Why this matters

The L2 cache tier is core infrastructure. A query with a warm L2 hit
serves a 64 KiB graph block from RAM (off-heap) or NVMe (disk) instead
of paying an S3 round-trip. Bugs here surface as:

- **Silent corruption** — a query reads a byte sequence belonging to a
  different segment because the allocator handed out an overlapping
  slot under load.
- **Resource leaks** — Arenas, file handles, mmap'd regions
  accumulating across long-running processes.
- **Crashes** — SIGSEGV from reading native memory whose Arena was
  closed by a concurrent eviction; JVM termination from
  `BufferOverflowException` past the mapped region.
- **Cold restarts** — index sidecar persistence drops entries the
  workload had just warmed.

Single-threaded unit tests catch happy-path correctness but cannot
explore the state space that matters in production: concurrent
allocators, eviction races, lifecycle interleavings, native-resource
hygiene, and persistence durability.

This document lays out a test harness scoped at "we tried hard to
break it." Aspirational, not a checklist — the harness should grow
with the providers.

## Scope

Two providers, both implementing `L2Provider`:

- **`OffHeapArenaL2Provider`** — JDK 21 FFM Arena per entry, in-memory.
- **`LocalDiskL2Provider`** — single mmap'd file, bump-pointer allocator
  with wrap, free-list reclaim, persisted index sidecar.

The harness should exercise both behind the `L2Provider` interface so
new implementations (chained tier, S3-backed, etc.) plug in for free.

## Threat model — what we are trying to break

### Concurrency hazards

1. **Concurrent get / put on the same key.** Reader observes either the
   pre-state or post-state, never a torn intermediate.
2. **Concurrent put / invalidate.** Final state is well-defined; no
   leaked Arena / no orphaned slot.
3. **Concurrent put / put on different keys.** Both succeed; total
   bytes stays ≤ maxBytes; no overlapping allocations.
4. **Concurrent get / eviction.** Reader never observes bytes from an
   evicted-and-reallocated entry under the original key.
5. **Concurrent put while close() runs.** Either the put completes
   cleanly or fails with a known exception. No SIGSEGV. No torn writes
   to the data file.
6. **Concurrent invalidateAll() / put.** Total bytes returns to zero
   monotonically; subsequent puts succeed.
7. **Memory ordering on counters / size accounting.** `currentBytes`,
   `entries.size()`, and the gauge readings agree at every quiescent
   point.

### Allocator boundary conditions

> **Resolved by the slab + LMDB redesign.** The custom bump-pointer
> allocator and free-list-by-size are gone. The slab tier has a fixed
> slot pool with explicit eviction; LMDB owns its own on-disk layout.

8. ~~**Bump-pointer wrap.**~~ No bump pointer.
9. ~~**Wrap-then-overlap eviction.**~~ No wrap, no overlap.
10. ~~**Same-size free-list reuse.**~~ No free-list-by-size.
11. ~~**Free-list growth under pathological workload.**~~ No free-list.
12. **Oversized put.** Both providers throw `IllegalArgumentException`
    for `bytes.length > maxBytes` (LMDB) or `bytes.length > blockSize`
    (slab) — loud rejection, not a silent drop. Covered by unit tests
    in `LmdbL2ProviderTest` / `SlabOffHeapL2ProviderTest`.

### Persistence (disk only)

> **Most resolved by the LMDB swap.** LMDB owns durability, crash
> safety, and disk integrity; the application no longer maintains a
> sidecar. Format-version skew remains relevant via LMDB's own
> versioning. Repeated open/close cycles are exercised by the
> `LmdbL2ProviderRestartCycleTest` in the harness.

13. **Clean restart preserves entries.** Covered by
    `LmdbL2ProviderTest.warmRestartPreservesKeySet` (16 keys,
    deterministic) and `LmdbL2ProviderRestartCycleTest` (20 default /
    100 nightly cycles, randomised workload).
14. ~~**Corrupt sidecar → cold start.**~~ No sidecar.
15. ~~**Truncated sidecar → cold start.**~~ No sidecar.
16. ~~**Missing sidecar with intact data file → cold start.**~~ No
    sidecar; LMDB env is the single source of truth.
17. ~~**Crash mid-persist.**~~ LMDB's copy-on-write makes this moot —
    a partial commit rolls back via the txn machinery.
18. **Format version skew.** LMDB has its own format versioning. Not
    currently exercised by the harness; future work if we ever bump
    the LMDB binding.
19. ~~**Bytes outside `maxBytes`.**~~ LMDB's `mapsize` is the hard
    ceiling; pre-emptive eviction at the soft cap (75 % of mapsize)
    keeps the working set bounded.
20. **Repeated open/close cycles.** Covered by
    `LmdbL2ProviderRestartCycleTest`.

### Resource hygiene

21. **Native memory growth (slab + LMDB).** Long-running workloads
    must not leak native memory. The slab tier's single shared
    `Arena.ofShared()` is closed deterministically in
    `SlabOffHeapL2Provider.close()`; LMDB's `Env.close()` releases the
    mmap. Covered by `L2ProviderSoakTest` (JFR
    `jdk.NativeMemoryUsage` recording, minute-5-vs-end-of-run anchor
    compare; Finding #21 below).
22. **File-handle leak (LMDB).** Repeated open/close cycles must not
    exhaust the process FD limit. Covered indirectly by
    `LmdbL2ProviderRestartCycleTest`'s 20-cycle (default) /
    100-cycle (nightly) churn.
23. ~~**Mmap leak (disk).**~~ LMDB owns the mmap lifecycle.
    `Env.close()` is deterministic; no `MappedByteBuffer` reliance on
    GC.
24. **File-lock release (LMDB).** LMDB's lock file is released on
    `Env.close()`. Construction-time failure of a second provider on
    the same directory is LMDB's responsibility; the harness doesn't
    explicitly assert this but the close-during-read race tests prove
    `close()` completes cleanly under contention.

### Lifecycle races

25. **close() during in-flight read.** Reader holding a shard lock
    completes; close waits on the same lock; post-close `get()`
    throws `IllegalStateException`, not SIGSEGV. Covered by
    `closeWaitsForInFlightShardLockAndPostCloseGetThrows` in both
    `LmdbL2ProviderTest` and `SlabOffHeapL2ProviderTest` (three-latch
    protocol with `invalidateMatching`'s predicate as the natural
    shard-lock injection point).
26. **Two providers on the same directory (LMDB).** Construction-time
    failure is LMDB's responsibility; not currently asserted by the
    harness. Future work.
27. ~~**Restart while data file is being written by another process.**~~
    LMDB's lock semantics handle this; not currently exercised.

### Failure injection

> **Most resolved by the LMDB swap.** LMDB owns disk-full handling,
> mmap construction errors, and crash recovery. The application's
> only error path is `Env.MapFullException`, which the in-memory LRU
> bookkeeping is designed around (state mutations follow
> `txn.commit()`).

28. ~~**Disk full mid-persist.**~~ LMDB owns persistence.
29. ~~**Disk full mid-write.**~~ LMDB raises `MapFullException`; the
    application logs at ERROR, rolls back the txn, and leaves
    in-memory state untouched (Phase 3 lesson #8). Not currently
    exercised by the harness.
30. ~~**mmap fails at construction.**~~ LMDB's `Env.create().open(...)`
    throws on construction failure; the constructor wraps the
    directory creation in `UncheckedIOException`.
31. ~~**Index sidecar deleted while running.**~~ No sidecar.

## Test framework approach

A layered approach — each layer catches a different class of bug.

### 1. Concurrent stress harness (the workhorse)

JUnit 5 + AssertJ + `ExecutorService`. The pattern:

- N worker threads, M operations per thread, parameterised
  read/write/invalidate ratios.
- Random keys from a configurable pool (small pool → high contention,
  large pool → eviction pressure).
- Random payload sizes within a range.
- A sequential reference model maintained out-of-band — at the end of
  the run, verified-against invariants (not full linearizability —
  too expensive — but post-hoc consistency: every key currently in
  the provider matches the reference's view of "what was last put or
  invalidated for this key").
- `ConcurrentHashMap`-backed reference; one writer per key in the
  reference plus a totals counter; "last writer wins" semantics
  matched against final provider state.
- Assertions: `currentBytes ≤ maxBytes` continuously (sampled);
  `currentBytes == sum(entries.length)` at end; no exceptions
  swallowed by workers; meter counters monotonically non-decreasing.

Six standard scenarios:

- **Read-heavy** — 90 / 9 / 1 (get / put / invalidate). Stresses lock
  contention on the read path.
- **Write-heavy** — 10 / 80 / 10. Stresses allocator + eviction.
- **Eviction churn** — small `maxBytes` (1 KiB), large key pool (1000),
  many writers. Should produce eviction counter spikes; verify no
  leaks.
- **Same-key contention** — 16 threads on a 4-key pool. Final state
  must satisfy `currentBytes == sum of {one chosen value per key}`.
- **Mixed-size payloads** — random length [1, payloadCap]. Catches
  free-list mismatches.
- **Periodic invalidateAll** — one thread invalidating every 100 ms
  while others write. `currentBytes` should oscillate without ever
  exceeding `maxBytes`.

Each scenario runs against both providers (parametrised on
`L2Provider`) so the harness validates the contract, not the
implementation.

### 2. Property-based testing

Light-touch, hand-rolled or via [jqwik](https://jqwik.net/). Generate
sequences of `(op, key, payload)` tuples; apply to both the provider
and a sequential reference; assert every observable matches.

The interesting properties:

- For any sequence: final `entries.keySet()` ⊆ keys ever inserted.
- For any sequence ending in `invalidateAll()`: final state empty.
- For any sequence: `currentBytes == sum of payload lengths for
  surviving keys`.
- For any sequence + restart cycle on disk: reload reproduces the
  surviving set.

Shrinking on failure to find the minimal sequence that breaks it. The
sequential reference is the oracle.

### 3. Targeted interleaving (jcstress)

The OpenJDK [jcstress](https://github.com/openjdk/jcstress) framework
explores fine-grained 2-thread interleavings. Worth using for the few
operations that explicitly touch shared mutable state outside the
single lock — gauge sampling threads, counter increments, the
`closed` flag.

Don't try to jcstress the whole provider. Pick targeted scenarios:

- Two threads incrementing the eviction counter via different paths
  (put-eviction vs wrap-eviction). Final count == sum of operations.
- Gauge collection thread reading `currentBytes` while a put runs.
  No torn long read (already guaranteed by the lock; jcstress
  documents the intent).

### 4. Failure injection

In-memory filesystem via [`jimfs`](https://github.com/google/jimfs) or
similar. Simulate:

- Disk full on `Files.write` (truncate quota mid-stream).
- Permission denied on `Files.move`.
- I/O error on `FileChannel.write`.

Run the full unit suite against the simulated filesystem. Provider
must not crash; observable state stays consistent.

### 5. Long-running soak

Single test, marked `@Tag("soak")`, excluded from the default suite,
runs in a nightly job. 30 minutes minimum. Random workload, periodic
invariant checks, JFR recording for native-memory + GC analysis.
Detection target: linear growth in any of {RSS, file-descriptor
count, heap retained size}.

### 6. Restart-cycle test (disk only)

100+ open/close cycles in a single test. Each cycle:

1. Open against the same directory.
2. Random write workload (parameterised seed for reproducibility).
3. Capture entries snapshot.
4. Close cleanly.
5. Reopen, verify reload matches the snapshot.

Catches subtle index-format / persistence bugs (e.g., access-order
not preserved across reload, byte-accounting drift).

## Tooling decision summary

| Layer | Tool | When |
|---|---|---|
| Concurrent stress | JUnit 5 + Threads | Default suite (default intensity), nightly profile (full intensity) |
| Property-based | jqwik | Default suite (100 tries) / nightly (1000 tries) |
| Interleaving | jcstress | Operator-driven via `-Pjcstress` (compile-only today) |
| Soak | JUnit + JFR | `@Tag("soak")`, opt-in via `-Psoak` |
| Restart cycle | JUnit | Default suite (20 cycles) / nightly (100 cycles) |

`jqwik` ships in the `vector-store-core` test scope; `jcstress` is gated
behind the `-Pjcstress` Maven profile (its annotation processor + class
generation is intrusive on default builds). `jimfs` is no longer used —
the original failure-injection layer was scoped to the deleted
`LocalDiskL2Provider`.

## Findings in legacy code (historical)

> The findings below referred to the pre-redesign providers
> (`LocalDiskL2Provider`, `OffHeapArenaL2Provider`). All concerns are
> superseded by the LMDB + slab redesign or by the harness's
> post-redesign findings #6 / #21 / #22 above. Retained only as a
> design-history pointer; do not act on them as written.

1. ~~`LocalDiskL2Provider` 2 GiB `maxBytes` cap.~~ Resolved by the
   LMDB swap; `mapsize` is `long`-indexed.
2. ~~`OffHeapArenaL2Provider.currentBytes` AtomicLong / lock
   inconsistency.~~ The provider class is gone; the slab tier reads
   `currentBytes` lock-free for stats / gauges and mutates under
   shard lock.
3. ~~`MappedByteBuffer` not explicitly unmapped.~~ LMDB owns the mmap;
   `Env.close()` is deterministic.
4. ~~Free-list growth.~~ No free-list-by-size; the slab uses a fixed
   slot pool.
5. ~~O(N) eviction overlap scan.~~ No bump pointer; eviction is
   per-shard LRU iteration.
6. **Lock contention.** Tracked as Finding #6 in the harness (per-shard
   throughput plateau).
7. ~~`MappedByteBuffer` thread-safety.~~ No `MappedByteBuffer`.
8. ~~Unbounded put queue.~~ Both providers acquire shard locks
   (per-key for slab, per-shard for LMDB) — no global queue.

## Harness findings tracking

Three named findings have constants in the test source. Failure
messages embed the captured quantitative payload so the operator can
decide whether to investigate or tune.

### Finding #6 — Per-shard contention (was: single-lock contention)

- **Where:** `L2ScalingTest.readHeavyThroughputScalesAcrossThreads`.
- **Default:** 1 thread → 4 threads on the slab provider, read-heavy.
  System-property knobs (`l2.scaling.baseline.threads`,
  `l2.scaling.target.threads`, `l2.scaling.multiplier`,
  `l2.scaling.ops.per.thread`, `l2.scaling.curve.threads`) let larger
  machines ramp up.
- **Threshold:** `THROUGHPUT_SCALING_MULTIPLIER = 1.5` — target ≥ 1.5×
  baseline.
- **Follow-up:** striped-lock granularity tuning if the slab fails.
- **LMDB note:** the diagnostic-curve method walks LMDB at
  `1, 4, 8, 16` threads but doesn't gate on the multiplier; LMDB read
  scaling is bounded by MVCC coordination, not the application-level
  shard lock.

### Finding #21 — Native memory growth (was: Arena allocation rate)

- **Where:** `L2ProviderSoakTest.steadyStateHasBoundedNativeMemoryGrowth`.
- **Measurement:** JFR `jdk.NativeMemoryUsage` events at one-minute
  granularity. Anchor compare: minute-5 sample (post-warm-up baseline)
  vs end-of-run sample.
- **Threshold:** delta ≤ 5 % of the minute-5 baseline. Sample-to-sample
  monotonicity is **not** asserted — RSS / heap fluctuates from JIT
  compilation, GC mark cycles, and eviction churn.
- **Follow-up:** Arena lifecycle audit; LMDB env page count check.

### Finding #22 — Single-flight breakdown (NEW)

- **Where:** `BlockCacheConcurrencyTest.singleFlightCollapsesConcurrentMissesAcross64Threads`.
- **Measurement:** total `vectorstore.cache.miss` count after 64
  threads × 1000 reads on 8 pre-seeded keys.
- **Threshold:** `LOADER_INVOCATION_BUDGET_PER_KEY × KEY_COUNT` (= 32
  with the defaults).
- **Follow-up:** Caffeine config audit; `recordStats()` wiring check.

## Acceptance bar (post-redesign)

- [x] All six concurrent stress scenarios pass against the slab, LMDB,
      and chain providers at default intensity (4 × 5k); nightly
      intensity (16 × 100k) under `-Pstress-nightly`.
- [x] jqwik property test runs 100 sequences (default) / 1000 sequences
      (nightly) with no failures.
- [x] Restart-cycle test runs 20 cycles (default) / 100 cycles
      (nightly).
- [x] `BlockCacheConcurrencyTest` asserts L1 miss count stays within
      the single-flight budget.
- [x] `closeDuringReadDoesNotCrash` test on both LMDB and slab.
- [ ] `L2ProviderSoakTest` runs 30 minutes per provider with no growth
      beyond the documented noise band (operator-driven via
      `-Pstress-nightly,soak -Dl2.soak.duration=PT30M`).
- [ ] jcstress smoke runs end-to-end (currently compile-only under
      `-Pjcstress`; full execution is future work).
- [x] Capture-path validation: temporarily lowering
      `THROUGHPUT_SCALING_MULTIPLIER` (via
      `-Dl2.scaling.multiplier=100`) confirms the harness fails with
      the embedded quantitative payload.

## Open questions

- Process-kill / hard-crash recovery harness. LMDB's copy-on-write
  makes the most acute concerns moot, but a forking process-kill
  harness is still future work.
- Cross-platform validation. macOS local only until CI lands.
- jcstress end-to-end execution. The smoke test class compiles under
  `-Pjcstress` and the annotation processor populates
  `META-INF/jcstress-tests/`; wiring `mvn verify -Pjcstress` to
  actually fork worker JVMs requires either a shaded executable jar
  or careful classpath plumbing through `exec-maven-plugin`. See the
  harness README for the operator-driven invocation pattern.
