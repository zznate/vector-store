# Contributing to vector-store

Thanks for the interest. This document covers the local checks every
change should pass before opening a pull request, the conventions the
project follows, and where to look for context on a given subsystem.

## Pre-PR check sequence

Run these from the repo root before opening a PR:

```
./mvnw install -DskipTests   # install all module artifacts to ~/.m2/
./mvnw verify                # full unit + integration tests across the reactor
./mvnw pmd:check             # complexity + correctness gate
./mvnw checkstyle:check      # lexical / style gate
```

All four must pass. CI does not run PMD or Checkstyle today (they are
manual gates), so a green local run is the contract.

The leading `install` step is only load-bearing the first time after a
new module is added (or a fresh checkout) — `pmd:check` resolves
inter-module dependencies through the local Maven repo and fails if a
sibling artifact has not been installed there yet.

| Gate | What it covers | Where the rules live |
|---|---|---|
| `mvn verify` | Unit tests (Surefire) + integration tests (Failsafe, including a Testcontainers-MinIO suite). Required. | per-module `src/test/...` |
| `mvn pmd:check` | Method / class size, cyclomatic + cognitive complexity, parameter list size, empty catch / control statements. | [`tools/pmd-ruleset.xml`](tools/pmd-ruleset.xml) |
| `mvn checkstyle:check` | Imports, braces, naming, line length (120), tabs, trailing newline. | [`tools/checkstyle.xml`](tools/checkstyle.xml) |

The `vector-store-datagen` module is excluded from PMD and Checkstyle:
it is CLI tooling outside the service module graph, with top-level
imperative `run()` methods that read like recipes by design.

If a gate flags a finding, prefer fixing it over suppressing it. A
suppression is acceptable only when the rule's intent does not apply
(see `CommitCoordinator`'s `@SuppressWarnings("PMD.ExcessiveParameterList")`
for the canonical example: a CDI `@Inject` constructor whose shape is
dictated by the framework, not by code-smell). Suppressions need a
one-line comment explaining why.

## Repository conventions

### Commits

[Conventional Commits](https://www.conventionalcommits.org/) format:
`<type>(<scope>): <imperative summary>`. Type is one of `feat`, `fix`,
`chore`, `docs`, `refactor`, `test`, `build`, `perf`, `style`, `ci`,
`revert`. Scope is the module name when applicable (`core`, `engine`,
`storage`, etc.) or omitted for repo-wide changes.

The body should describe what changed and why at the surface level —
enough that a future reader can understand the blast radius without
reading the diff in full. Keep it concise; no walls of text. Do not
reference workflow artifacts that live outside the source tree (prompt
files, slice IDs, internal task numbers).

### Code style

- Java 21. JVector's Panama-Vector code path requires
  `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED
  --enable-preview` at every JVM entry point. The parent POM wires
  these for compile, surefire, failsafe; container images and dev
  runs need them via `JAVA_TOOL_OPTIONS`.
- 2-space indentation, LF line endings, UTF-8.
- Records, sealed types, and immutable values where applicable.
- Methods stay small and focused on one concern. The PMD gate flags
  methods over ~40 statements or cognitive complexity 15; favour
  named helpers over comment-separated regions.
- `catch` blocks must log the throwable with full stack trace at a
  level operators will see (or rethrow). PMD's `EmptyCatchBlock`
  enforces this; a non-rethrowing catch must include
  `LOG.warn("...", e)` or equivalent.
- Log calls with method invocations, string concatenation, or other
  non-trivial argument evaluation must be guarded by an
  `isXxxEnabled()` check, even when SLF4J placeholders are used.
- Default to no comments on code. Only add a comment when the *why* is
  non-obvious — a hidden constraint, a subtle invariant, or a
  workaround for a specific bug.

### Testing

- Unit tests for every new public surface. Test the smaller helper
  before the integrated flow it composes into; both should have
  coverage.
- Integration tests in `vector-store-app/src/test/.../it/` use
  Testcontainers MinIO and run as part of `./mvnw verify`.
- No mocked databases — integration tests hit a real catalog.

### Documentation

- Each module's `README.md` describes that module's role, public
  surface, dependencies, and what is intentionally out of scope.
- The cache configuration reference lives in
  [`vector-store-core/README.md`](vector-store-core/README.md#cache-configuration-reference);
  every cache key is documented there once.
- [`docs/design-notes.md`](docs/design-notes.md) is the authoritative
  design surface. If code disagrees with the design notes, fix the
  code unless the disagreement is itself a deliberate change to the
  design — in which case update the notes in the same PR.

## Where to look

| Looking for | Read |
|---|---|
| The phase plan and overall shape | [`README.md`](README.md) |
| Authoritative design (invariants, schema, observability) | [`docs/design-notes.md`](docs/design-notes.md) |
| What each module owns | each `vector-store-*/README.md` |
| Cache configuration keys + tuning intent | [`vector-store-core/README.md`](vector-store-core/README.md) |

## License

Apache License 2.0. Contributions are accepted under the same licence.
See [`LICENSE`](LICENSE).
