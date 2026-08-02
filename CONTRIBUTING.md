# Contributing

Thanks for contributing! The build is a standard IntelliJ Platform Gradle project, but there are
three project-specific tripwires that catch almost every new contributor. Read those first.

## Build and Test Quickstart

```bash
# JDK 21 is REQUIRED — see tripwire 1 below
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; on Linux point at any JDK 21

./gradlew build          # compile + test + verify
./gradlew test           # whole suite, platform tests included — ~40s headless
./gradlew runIde         # launch a sandbox IDE with the plugin installed
```

Run `./gradlew test` before pushing. There is no separate "fast tier" — the suite is small enough
that splitting it would cost more than it saves.

## The Three Tripwires

### 1. JDK 21 is required, and your shell default probably is not

The project compiles against JDK 21. Many machines default to an older JDK (commonly 8), which
fails with confusing errors long before it tells you the real problem. Always override
`JAVA_HOME` for Gradle commands:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### 2. `pumpingEdt { }` in platform tests — or a misleading 30-second timeout

`BasePlatformTestCase` runs test methods **on the EDT**, and tools like `set_breakpoint` hop onto
the EDT themselves. A plain blocking HTTP call from a test therefore deadlocks — and surfaces as
a baffling 30-second `HttpTimeoutException`, not as a deadlock.

**Wrap any tool call that mutates IDE state in `pumpingEdt { ... }`.** Read
`src/test/kotlin/.../server/transport/McpHttpTestSupport.kt` before writing HTTP-level tests; new
transport or tool-behaviour tests should extend `McpHttpTestCase`, which boots the real
`KtorMcpServer` on a free port.

Related: write fixture files to disk under `project.basePath`, not via
`myFixture.addFileToProject` — `VirtualFileResolver` resolves through `LocalFileSystem`, which
cannot see the in-memory `TempFileSystem`.

### 3. Golden contract regeneration is deliberate, never routine

`src/test/resources/contract/tool-manifest.txt` and `result-shapes.txt` are **contracts with MCP
clients**: every tool schema and every result model's wire shape. Result models use plain Kotlin
property names as wire keys, so an IDE "Rename" on a result property is a source-compatible
change that silently breaks every client — the snapshot is what catches it.

If a contract test fails, do not reflexively regenerate. Decide whether the wire change is
intended. If it is:

```bash
./gradlew test --tests "*ToolManifestContractTest" -Dcontract.update=true
```

Review the resulting diff as part of your change — **the diff is the list of breaking changes the
release notes owe clients.** During large refactors (such as an MCP SDK migration), regeneration
is frozen entirely: a non-empty diff in `src/test/resources/contract/*.txt` is the definition of
a client-breaking change, and such branches must land with zero diff in those files.

## Where Things Live

- `CLAUDE.md` — the honest engineering doc: test-suite architecture, known gaps, MCP structured
  output rules. It is the most accurate document in the repo; when it and another doc disagree,
  trust `CLAUDE.md` and fix the other one.
- `src/test/kotlin/.../contract/` — golden-contract tests (client-facing surface).
- `src/test/kotlin/.../server/transport/` — HTTP conformance tests (routes, headers, SSE frames).
- `src/test/kotlin/.../tools/**/*BehaviorTest` — what a tool actually does to IDE state.

## Pull Requests

1. Fork, create a feature branch (`feat/`, `fix/`, `chore/`, `test/` prefixes are the convention)
2. Make your changes, following existing patterns
3. Add or update tests in the same style as the surrounding suite
4. Run `./gradlew test` with JDK 21
5. Open a PR against `main`
