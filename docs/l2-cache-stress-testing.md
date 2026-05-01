# L2 cache stress-testing plan

> Status: planning. Lands as the testing follow-up to prompt 07a slice 1
> (`LocalDiskL2Provider`) and the off-heap counterpart we already had
> (`OffHeapArenaL2Provider`). No code in this document — research only.

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

8. **Bump-pointer wrap.** Many consecutive puts that wrap exactly at
   `maxBytes`. Verify no overflow in offset arithmetic.
9. **Wrap-then-overlap eviction.** A wrapped write that touches three
   adjacent prior entries evicts all three; no partial eviction.
10. **Same-size free-list reuse.** Invalidate, put same size, put same
    size — second put bump-allocates; doesn't reuse the same slot
    twice.
11. **Free-list growth under pathological workload.** Repeatedly put
    then invalidate millions of distinct sizes — does the free list
    grow without bound? (Likely yes; need a cap.)
12. **Oversized put.** Payload ≥ maxBytes returns silently without
    side effects (no partial write, no torn allocOffset).

### Persistence (disk only)

13. **Clean restart preserves entries.** Open, write, close, reopen —
    every entry's bytes match.
14. **Corrupt sidecar → cold start.** Magic byte zeroed → empty cache
    on restart, no crash.
15. **Truncated sidecar → cold start.** Sidecar written half-way →
    cold start; unread bytes do not surface.
16. **Missing sidecar with intact data file → cold start.** Don't
    return phantom hits derived from stale data-file contents.
17. **Crash mid-persist.** Kill the JVM during `persist()` — atomic
    rename should leave the prior sidecar intact (verify via
    file-system state after externally interrupting `persist`).
18. **Format version skew.** Bump `VERSION` in a synthesised sidecar
    → cold start.
19. **Bytes outside `maxBytes`.** Synthesised sidecar references
    `offset + length > maxBytes` → cold start.
20. **Repeated open/close cycles.** 1000+ cycles with random write
    workloads. Each cycle's reload matches its predecessor's write
    set.

### Resource hygiene

21. **Arena leak (off-heap).** Open / fill to capacity / close in a
    loop — native memory does not grow unboundedly. Use
    `ProcessHandle` + RSS sampling, or attach JFR.
22. **File-handle leak (disk).** 10k open/close cycles do not exhaust
    the process file-descriptor limit.
23. **Mmap leak (disk).** `MappedByteBuffer` is not explicitly
    unmapped in `close()` — relies on GC. On Windows specifically,
    document any handle lingering. May need an explicit `Unsafe.invokeCleaner`
    if leaks materialise (internal JDK API; deferred until evidence).
24. **File-lock release.** After `close()`, another provider on the
    same directory acquires the lock immediately — no orphaned lock.

### Lifecycle races

25. **close() during get().** Reader holding the lock completes; close
    waits; subsequent get returns Optional.empty (or a known
    exception, document which).
26. **Two providers on the same directory.** Second constructor
    fails fast with `IOException`. The first still functions.
27. **Restart while data file is being written by another process.**
    Pathological but worth probing — the file lock should reject the
    second opener.

### Failure injection

28. **Disk full mid-persist.** Mock filesystem returning ENOSPC.
    Provider logs at WARN, doesn't crash, doesn't corrupt the
    pre-persist sidecar.
29. **Disk full mid-write.** Bump-allocator can't extend the data
    file (it's pre-allocated, so this shouldn't happen — but verify
    behaviour if the pre-allocate itself was incomplete).
30. **mmap fails at construction.** Inadequate address space (32-bit
    JVM? not our deployment target, but worth a smoke test) or
    permission errors → `UncheckedIOException` with clear message;
    no half-constructed provider visible to callers.
31. **Index sidecar deleted while running.** Should not affect
    in-memory state until `close()`; close re-creates it.

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
| Concurrent stress | JUnit 5 + ExecutorService | Standard suite, every PR |
| Property-based | jqwik | Standard suite, may need a longer timeout |
| Interleaving | jcstress | Manual / nightly; not on PR critical path |
| Failure injection | jimfs | Standard suite |
| Soak | JUnit + JFR | Nightly tag-excluded |
| Restart cycle | JUnit, parameterised | Standard suite |

Add `jqwik`, `jimfs`, `jcstress` (test-scope) to the `vector-store-core`
POM when slice 2/3 lands. Keep `jcstress` opt-in via a Maven profile —
its build pipeline is intrusive.

## Findings in current code

Discovered during the slice-1 review. Items marked **🔥** block prompt
07a's stated functionality and should be addressed inside slice 2 or
3; the rest are deferred until the testing harness exposes them as
real-world problems.

1. ~~**🔥 `LocalDiskL2Provider` cannot honor the prompt's 10 GiB default
   `maxBytes`.**~~ **Resolved in slice 1 follow-up.** Switched from
   `MappedByteBuffer` to `MemorySegment` via JDK 21's
   `FileChannel.map(MapMode, long, long, Arena)` overload. The new
   API is `long`-indexed throughout, so configurations >2 GiB succeed
   at construction. As a bonus, the Arena owns the mapping lifecycle
   so {@code close()} unmaps deterministically rather than relying on
   GC — partially addresses finding #3 below for the disk path.
   `supportsMaxBytesAbove2GiB` regression test pins the behaviour at
   3 GiB (sparse, no actual allocation).
2. **`OffHeapArenaL2Provider.currentBytes` is `AtomicLong` but only
   mutated under lock.** Inconsistent — should be plain `long`, or
   the lock should go (which would require rethinking the lifecycle
   of the LinkedHashMap). Style only; flagged for the cleanup pass.
3. ~~**`MappedByteBuffer` is never explicitly unmapped in
   `LocalDiskL2Provider.close()`.**~~ **Resolved in slice 1
   follow-up via Finding #1's `MemorySegment` switch.** The Arena
   owns the mapping; `arena.close()` unmaps deterministically. Soak
   tests in #15 should still verify no FD / RSS growth across many
   open/close cycles, but we no longer rely on GC for cleanup.
4. **Free-list has no growth cap.** `releaseToFreeList` adds to a
   `Map<Integer, ArrayDeque<Long>>` keyed by exact byte size. A
   workload that puts and invalidates many distinct sizes leaks
   memory in the free-list itself (offsets are 8 bytes each, but
   millions add up). Likely won't hit in normal use; a cap of N
   buckets / total entries is a future-work item.
5. **`evictOverlapping` is O(N) per put.** Iterates the entire
   `entries` map looking for overlaps. Fine for our typical
   working set (256-1000 entries) but worth measuring under
   eviction churn. If it shows up in profiling, an interval-tree or
   sorted-by-offset auxiliary index would amortise it.
6. **Lock contention is the obvious throughput bottleneck.** Single
   `ReentrantLock` around every operation. Acceptable for an L2
   tier sized for read-heavy workloads, but throughput tests will
   surface it. Striped locking (lock per shard, where shard is
   `key.hashCode() % N`) is the natural future evolution.
7. **`MappedByteBuffer` `put(int, byte[], int, int)` and `get(int,
   byte[], int, int)` are absolute bulk operations introduced in JDK
   13; not strictly required to be thread-safe per the JavaDoc.** The
   single lock around them makes this moot, but if we ever try to
   shed the lock the assumption needs revisiting (and may force
   striping or a different access pattern).
8. **No back-pressure on `put` when the lock is contended.** Threads
   queue at the lock indefinitely. For `block` cache puts driven by
   user queries, an unbounded queue under load could amplify latency
   tail. Worth a brief look during the load tests.

## Acceptance bar for the testing follow-up

Before declaring 07a complete:

- [ ] All six concurrent stress scenarios pass against both providers
      with N=16 threads, 100k ops each, three runs in a row.
- [ ] Property-based test with 1000 random sequences of length 100
      passes for both providers.
- [ ] Restart-cycle test with 100 cycles passes.
- [ ] Failure-injection scenarios all produce documented exceptions
      and consistent post-state.
- [ ] Soak test (30 min) shows no growth in RSS / fd count / heap
      retained size beyond steady-state noise.
- [ ] Finding #1 (the 2 GiB cap) is resolved, either by capping or
      multi-segment.

## Open questions

- Do we want a chaos-monkey-style test that randomly kills the JVM
  via `Runtime.halt()` at varying points and validates restart
  correctness? Effective for finding atomicity holes, expensive to
  develop. Maybe slice 4.
- Property-based testing depth — can jqwik run sequences long enough
  (10k+ ops) to catch eventually-emergent bugs without hitting the
  surefire timeout? May need a parallel Maven profile.
- jcstress integration — opt-in via `mvn -Pjcstress`? Run on a
  nightly job? Don't run at all and rely on the stress harness?
  Defer the call until the harness is up and we see whether the
  stress harness alone is catching enough.
