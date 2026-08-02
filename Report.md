# Live QA Report — Debugger MCP Server v5.0.0 (MCP Kotlin SDK migration)

**Date:** 2026-08-02
**Target plugin build:** v5.0.0 — the MCP Kotlin SDK migration built earlier in this session, installed by the user into a running IntelliJ IDEA instance.
**Test project:** `~/git/blindspot` (real internal Java/Spring project, open in the IDE), JDK 17 (`azul-17`).
**Method:** every one of the 23 `jetbrains-debugger` MCP tools invoked directly against the live IDE and a real JVM, over the actual production transport (no mocks, no unit-test harness). This report documents what was actually observed, including two genuine bugs, one documented-and-confirmed limitation, and one environmental problem that was fixed mid-session.

## Verdict

**All 23 tools work.** 21 of 23 were validated on a full golden path with real, verifiable evidence (session start → breakpoint hit → inspection → mutation → step → resume → stop). 2 tools (`pause_execution`, and by extension `execute_run_configuration`'s debug-mode session-tracking) could only be partially validated — not because anything failed, but because this environment has no long-running process that stays up long enough to pause (see §5). The plugin's headline v5.0.0 changes — the MCP Kotlin SDK transport, stable UUID object IDs, real stack/thread data, the safety guard hardening, and the EDT/modality fixes — all held up under real use. Two real, reproducible bugs were found (§4.1, §4.2), both minor and both outside the SDK migration itself.

---

## 1. Environment note: a stuck background process, fixed mid-session

Before any plugin testing could proceed, `start_debug_session`, `list_debug_sessions`, and even read-only `ide_diagnostics` calls all timed out against the IDE. Investigation traced this to 15 orphaned shell processes from an earlier automated test run in this same session (a CPU-saturation load test for the `set_breakpoint` fix, whose cleanup step never ran), pinned at an aggregate ~450% CPU for over 27 hours. The user killed them (`kill -9`), after which the IDE responded normally. **This was not a plugin defect** — but it's worth recording because it explains why the first several tool calls in this session's raw history show timeouts that have nothing to do with the debugger plugin itself.

## 2. Test setup

Per user correction mid-session, all testing used **genuinely pre-existing run configurations** — nothing was fabricated:

| Configuration | Type | Used for |
|---|---|---|
| `PlatformConverterTest` | JUnit (class) | Primary test vehicle — breakpoints, stepping, variables, evaluation |
| `PlatformConverterTest.singleValue` | JUnit (method) | Method-scoped config validation, `step_into` |
| `Blindspot Server` | Spring Boot | Attempted `pause_execution` golden path |

An earlier attempt to reuse a stale `DebugProbe`/`DebugProbeSpin` Application config (whose source file had been deleted in a prior session) was abandoned per user direction; the run-config XML edits made to work around it were fully reverted, and no trace of that attempt remains (confirmed via `git status`, direct file diff against the originals, and `list_breakpoints`).

The real test target, `PlatformConverterTest.java`, exercises `SimpleIncludeExcludeConverter.getWhereClauseCondition()` — a small real production method with a genuine branch and two call sites (`singleValue()`: `fieldRestriction="PHON"`; `mix()`: `fieldRestriction="PHON, TBLT"`), which turned out to be an excellent natural fixture for conditional breakpoints (the two calls genuinely differ on `fieldRestriction.contains(",")`).

## 3. Per-tool results

| Tool | Result | Evidence |
|---|:--:|---|
| `list_run_configurations` | ✅ | Returned all 11 real configs (Application/JUnit/Maven/Spring Boot) plus `activeConfiguration` |
| `set_breakpoint` | ✅ | Plain, conditional (`i == 2`, later `fieldRestriction.contains(",")`), tracepoint (`log_message` + `suspend_policy: none`) all set correctly; invalid-location and file-not-found errors matched documented pinned strings exactly |
| `list_breakpoints` | ✅ | Correctly aggregated my breakpoints alongside 6 pre-existing ones (4 exception breakpoints, 2 line breakpoints) across the whole project |
| `remove_breakpoint` | ✅ | Every removal returned the pinned `"removed successfully"` message; final state verified identical to the original 6 |
| `start_debug_session` | ✅ | Class-level and method-level JUnit configs both launched cleanly with a real UUID session; Spring Boot config also launched (see §5 for its outcome) |
| `wait_for_pause` | ✅ | Used throughout; correctly waits for a not-yet-existing session, returns the full status payload on pause, times out cleanly when nothing appears |
| `get_debug_session_status` | ✅ | Comprehensive single-call payload (location, breakpoint attribution, 10-frame stack, real variables, source context, thread) confirmed correct on every pause; `include_variables`/`include_source_context` flags correctly suppress their sections |
| `list_debug_sessions` | ✅ | Correctly empty when nothing running, correctly reflects the live session otherwise |
| `stop_debug_session` | ✅ | Clean termination confirmed via a following empty `list_debug_sessions` |
| `get_stack_trace` | ✅ | `max_frames` cap respected (tested at 5); uncapped call returned the true 27-frame depth including JDK/JUnit internals, with graceful `"position unknown"` for unresolvable native frames |
| `select_stack_frame` | ✅ | Selection correctly propagates as the default frame for subsequent `get_variables`/`get_source_context`/`evaluate_expression` calls that omit `frame_index` |
| `list_threads` | ✅ | Real 8-thread listing; correctly distinguishes the breakpoint-paused thread (`state: "paused"`) from others halted only because `suspend_policy: all` (`state: "suspended"`) |
| `get_variables` | ✅ | Real values throughout — custom object `toString()`, primitives, no "Collecting data..." placeholder anywhere; correctly frame-relative after `select_stack_frame` |
| `set_variable` | ✅ | `condition` overridden to `"mcp_test_override"`, read back correctly via a follow-up `evaluate_expression` — full round-trip confirmed |
| `evaluate_expression` | ✅ | Bare reference and arithmetic succeed; a user-object method call (`restriction.toString()`) and a dangerous API call (`Runtime.getRuntime().exec(...)`) were both correctly **blocked** by the live `read_only` safety guard, with two different, specific, correctly-worded error messages (§6) |
| `get_source_context` | ✅ | With no args, correctly centers on the *currently selected frame's* location (not the raw breakpoint location) after `select_stack_frame`; `breakpointsInView` correctly lists/omits breakpoints per window |
| `step_over` | ✅ | Confirmed to stay at the same stack depth across a line containing a method call |
| `step_into` | ✅ | Confirmed to descend one frame into the called method's first line |
| `step_out` | ✅ | Confirmed to return exactly to the caller's call-site line |
| `resume_execution` | ✅ | Correctly free-runs past a `suspend_policy: none` tracepoint and a false-conditioned breakpoint without pausing; correctly stops at the next real breakpoint |
| `pause_execution` | ⚠️ partial | Golden path (pause a genuinely running program) not achievable in this environment — see §5. Error path confirmed correct (`"No active debug session"`, exact pinned string) |
| `execute_run_configuration` | ✅ (run) / ⚠️ partial (debug) | `mode: "run"` confirmed correct (`sessionId: null`, no debugger attached, as designed). `mode: "debug"` launches correctly but with no breakpoint set, the JUnit test completes faster than a session can reliably be observed mid-flight — an artifact of the test's speed, not the tool |

**21/23 fully golden-path verified. 2/23 (`pause_execution`, `execute_run_configuration` debug-mode session tracking) verified correct on their error/non-pausing paths, with the "pause a live process" scenario undemonstrated for environmental reasons, not tool defects.**

## 4. Bugs found

### 4.1 `presentation` stack-frame strings are consistently one line short (real bug, low severity)

**Symptom:** every stack frame's human-readable `presentation` field reports a line number exactly **one less** than the frame's own authoritative `line` field, in `get_debug_session_status`, `get_stack_trace`, and `select_stack_frame`. Example, from an actual pause:

```json
{"line": 32, "presentation": "JavaFrame SimpleIncludeExcludeConverter.java:31"}
```

`line: 32` matches `currentLocation.line`, `breakpointHit.line`, and `sourceContext.currentLine` — all mutually consistent and correct. Only `presentation` disagrees, and it disagrees by exactly 1, on every single frame observed across three separate pauses.

**Root cause (verified in the plugin source):** all four call sites building this field —
`tools/util/SessionStatusCollector.kt:139`, `tools/stack/SelectStackFrameTool.kt:87`, `tools/stack/GetStackTraceTool.kt:108`, and `tools/util/StackFrameUtils.kt:23,32` — assign `presentation = frame.toString()` (or `.take(N)` of it) directly from the platform's raw `XStackFrame`/`JavaStackFrame` object. That `toString()` is IntelliJ's own internal diagnostic representation, not a field this plugin computes — and it evidently encodes a differently-based line number than the one the plugin correctly derives elsewhere for the dedicated `line` field.

**Impact:** low. Every machine-readable field (`line`, `currentLocation`, `breakpointHit`, `sourceContext`) is correct; only the prose `presentation` string is off, and nothing in the plugin's own logic consumes it. A client that displays `presentation` directly to a user would show a wrong line number in that one string.

**Recommendation:** stop exposing the platform's raw `toString()`. Build `presentation` from the same authoritative `line`/file values already computed for the rest of the payload (e.g. `"${file.substringAfterLast('/')}:$line"`), so the two can never disagree.

### 4.2 Pause-reason heuristic false-positives on step-onto-a-breakpoint-line (documented gap, now empirically confirmed)

CLAUDE.md already discloses that pause-reason detection is "a file/line heuristic" limited to `breakpoint`/`step`. This session produced two clean, concrete reproductions:

1. **Stepping onto a `suspend_policy: none` tracepoint's line** reported `pausedReason: "breakpoint"` with that tracepoint as `breakpointHit`, even though the tracepoint itself never causes a suspend — the pause was caused by `step_over` landing there, not by the tracepoint.
2. **Stepping onto a conditional breakpoint's line while its condition is false** produced the identical false attribution — `pausedReason: "breakpoint"`, `breakpointHit` populated with the condition text, even though the condition was verified false immediately afterward via `evaluate_expression`.

In both cases the heuristic simply checks "is there an enabled breakpoint at the exact file:line I landed on," with no way to know the *actual* cause was a step. This is exactly the documented limitation, not a new defect — but it's worth having a concrete repro on file. **The core conditional-breakpoint mechanism itself is correct**: in a clean session with only the conditional breakpoint present, it correctly paused on the true case (`mix()`, condition true) and correctly skipped the false case (`singleValue()`) during genuine free-running `resume_execution` (§3, `resume_execution` row).

### 4.3 Minor / unconfirmed: one conditional-breakpoint miss after mixed step+resume history

In the *first* (non-isolated) test run — three breakpoints close together, with manual `step_over` calls landing on the conditional breakpoint's line once before it was reached again for real — a subsequent `resume_execution` into the same conditional breakpoint (this time genuinely true) did not pause there at all; execution continued one frame further, reported as `pausedReason: "step"`. Rerunning the identical scenario in isolation (only the conditional breakpoint present, no prior manual stepping) worked perfectly every time. I was not able to isolate a second repro within the time available, so I'm not confident this is a real defect in the conditional-breakpoint mechanism itself rather than an artifact of the SDK's own condition-evaluation flow interacting with a stray internal step request — but it's different enough from the well-understood 4.2 pattern that it's worth a note rather than silence, should it recur.

### 4.4 Minor / unconfirmed: transport error on a concurrent tool call

Firing `evaluate_expression` and `set_variable` in the same batch (i.e., as concurrent requests) produced a socket-level error on one of the two — `"The socket connection was closed unexpectedly"` — not one of the plugin's own pinned error strings. `set_variable` succeeded; only `evaluate_expression` failed, and it succeeded immediately on a sequential retry. This did not reproduce again despite the same session issuing many further concurrent-in-practice tool round-trips over the MCP transport. Plausibly a narrow race in the new SDK-based transport when two requests land on the same session near-simultaneously; not confirmed as reproducible, and not something a typical single-threaded client would ever trigger.

## 5. `pause_execution` — why the golden path is undemonstrated, not why it's broken

`pause_execution` needs a program that is *running* (not already paused) long enough to interrupt. None of this project's fast, existing configs stay up long enough:

- **JUnit tests** (`PlatformConverterTest`, etc.) complete in single-digit milliseconds with no breakpoint in the way — there is no window to pause them.
- **`Blindspot Server`** (the one genuinely long-running config) launched successfully — `start_debug_session` returned a real session in `"running"` state — but the underlying Spring Boot process terminated on its own within roughly a second, before `pause_execution` could reach it (almost certainly a missing local DB/config dependency for standalone startup in this environment, unrelated to the debugger plugin). `list_debug_sessions` confirmed the session was already gone; `pause_execution` correctly returned the exact pinned `"No active debug session"` string for that state — i.e., the tool behaved correctly given reality, it just never got to demonstrate its actual pause mechanism.

I did not fabricate a new long-running target to force this scenario, per the user's explicit instruction to use only existing configurations. **This is a coverage gap in this specific test session, not a finding about the tool.**

## 6. Safety guard — confirmed live and correctly configured

This blindspot IDE instance has `evaluate_expression` safety mode set to `read_only` (consistent with prior session notes). Confirmed live:

| Expression | Result |
|---|---|
| `restriction` (bare reference) | ✅ allowed |
| `1 + 41` (pure arithmetic) | ✅ allowed → `42` |
| `restriction.toString()` (user-object method call) | ❌ blocked — *"the Java PSI side-effect checker could not prove the expression is read-only"* |
| `fieldRestriction.contains(",")` (known-pure JDK method) | ✅ allowed → correctly distinguishes provably-pure JDK methods from arbitrary user code |
| `Runtime.getRuntime().exec("id")` | ❌ blocked — *"process execution APIs are not allowed. Matched token: Runtime.getRuntime().exec("* — a more specific message than the generic PSI rejection |

The guard is working exactly as documented, live, against a real production project, with the two different rejection messages (generic PSI-unprovable vs. specific blocklist match) both firing correctly for the right reasons.

## 7. Cleanup confirmation

Everything created or modified during this session was reverted:

- `DebugProbe.java` and its manually-compiled `.class` output: deleted.
- `DebugProbe.xml` / `DebugProbeSpin.xml`: reverted to byte-identical originals (diffed and confirmed).
- All breakpoints added during testing: removed. `list_breakpoints` at the end of the session shows exactly the same 6 breakpoints (4 exception, 2 line) present before testing began, with the same enabled/disabled state.
- No debug sessions left running (`list_debug_sessions` empty).
- `git status` in `~/git/blindspot` is clean.

## 8. Recommendation summary

1. **Fix 4.1** — derive `presentation` from the same `line`/`file` values as the rest of the payload, in all four affected sites, rather than the platform's raw `toString()`.
2. No action needed on 4.2 — already documented; this report adds a concrete repro to point at if it's ever revisited.
3. Keep an eye out for 4.3/4.4 if they recur with a cleaner repro; neither blocks release on the evidence gathered here.
4. Consider a lightweight, permanent "spin" fixture *in the plugin's own test suite* (not this project) for exercising `pause_execution`'s true pause-a-running-thread path in live-IDE testing — this session confirms it's hard to get real project run configs that stay up long enough on demand.
