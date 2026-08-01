# JetBrains Debugger MCP Plugin

This plugin exposes JetBrains IDE debugger capabilities through MCP (Model Context Protocol), enabling AI agents to programmatically debug applications.

## Quick Start

```
IMPORTANT: When debugging, prefer using jetbrains-debugger MCP tools to interact with the IDE debugger.
```

## Typical Debugging Workflow

1. **Discover configurations**: `list_run_configurations` to see available run/debug configs
2. **Start debugging**: `start_debug_session` with a configuration that has `can_debug: true`
3. **Set breakpoints**: `set_breakpoint` at locations of interest
4. **Resume and wait**: `resume_execution` then `wait_for_pause` with a timeout to block until a breakpoint is hit
5. **Inspect state**: `wait_for_pause` returns variables, stack trace, and source context automatically. Use `get_debug_session_status` if you need to re-inspect without waiting.
6. **Evaluate expressions**: `evaluate_expression` to test hypotheses
7. **Step through code**: `step_over`, `step_into`, `step_out` as needed
8. **Repeat steps 5-7** until the issue is found

## Tool Reference

### Session Management
| Tool | Description |
|------|-------------|
| `list_run_configurations` | List available run configurations |
| `execute_run_configuration` | Run a configuration (debug or run mode) |
| `list_debug_sessions` | List active debug sessions |
| `start_debug_session` | Start a new debug session |
| `stop_debug_session` | Stop a debug session |
| `get_debug_session_status` | Get comprehensive session state (variables, stack, source) |

### Breakpoints
| Tool | Description |
|------|-------------|
| `list_breakpoints` | List all breakpoints |
| `set_breakpoint` | Set a line breakpoint (supports conditions, log messages) |
| `remove_breakpoint` | Remove a breakpoint by ID |

### Execution Control
| Tool | Description |
|------|-------------|
| `resume_execution` | Resume from paused state |
| `pause_execution` | Pause running execution |
| `step_over` | Step to next line (over function calls) |
| `step_into` | Step into function call |
| `step_out` | Step out of current function |
| `run_to_line` | Run until specific line is reached |
| `wait_for_pause` | Wait for session to pause (breakpoint, exception, manual). Returns full status. |

### Inspection
| Tool | Description |
|------|-------------|
| `get_stack_trace` | Get call stack for a thread |
| `select_stack_frame` | Change stack frame context |
| `list_threads` | List all threads |
| `get_variables` | Get variables in current frame |
| `set_variable` | Modify variable value at runtime |
| `evaluate_expression` | Evaluate expression or code fragment |
| `get_source_context` | Get source code around a location |

## Best Practices

### Use `get_debug_session_status` First
When paused at a breakpoint, call `get_debug_session_status` before other inspection tools. It returns:
- Current location (file, line, method)
- Variables in scope
- Stack trace summary
- Source code context
- Breakpoint that was hit

This reduces round-trips compared to calling `get_variables`, `get_stack_trace`, and `get_source_context` separately.

### Conditional Breakpoints for Loops
When debugging loops or frequently-called functions, use conditional breakpoints:
```json
{
  "file_path": "/src/Calculator.java",
  "line": 42,
  "condition": "i == 100"
}
```

### Log Breakpoints for Tracing
Use log breakpoints (tracepoints) to trace execution without stopping:
```json
{
  "file_path": "/src/Calculator.java",
  "line": 42,
  "log_message": "Value of x: {x}, items.size(): {items.size()}",
  "suspend_policy": "none"
}
```

**Log Message Syntax**: Use `{expression}` placeholders in your log message. They are automatically transformed to language-specific expressions:

| Language | Input | Transformed To |
|----------|-------|----------------|
| Java | `"x={x}, y={y}"` | `"x=" + (x) + ", y=" + (y)` |
| Kotlin | `"x={x}, y={y}"` | `"x=$x, y=$y"` |
| Python | `"x={x}, y={y}"` | `f"x={x}, y={y}"` |
| JavaScript/TS | `"x={x}, y={y}"` | `` `x=${x}, y=${y}` `` |

You can also pass raw language-specific expressions directly (e.g., `"\"x=\" + x"` for Java) - they pass through unchanged if no `{...}` placeholders are detected.

### Breakpoints in Library Sources (JARs)
JAR-source breakpoints use the `!/` path form (e.g. `/path/to/lib-sources.jar!/com/example/Foo.kt`) and require the JAR to be attached to the project (library sources).

### Evaluate Before Modifying
Before using `set_variable`, use `evaluate_expression` to preview the change:
```json
{
  "expression": "calculateNewValue(currentValue)",
  "allow_side_effects": false
}
```

### Handle Multiple Projects
When multiple projects are open in the IDE, always specify `project_path`:
```json
{
  "project_path": "/Users/dev/my-project",
  "file_path": "/src/Main.java",
  "line": 10
}
```

If omitted with multiple projects, tools return an error listing available projects.

## Common Patterns

### Find Why a Value is Wrong
```
1. set_breakpoint at the line where the wrong value is used
2. start_debug_session
3. resume_execution (wait for breakpoint)
4. get_debug_session_status (see all variables)
5. evaluate_expression to test the calculation
6. step_over/step_into to trace the logic
```

### Debug a Specific Iteration
```
1. set_breakpoint with condition "i == 50"
2. start_debug_session
3. resume_execution
4. Debugger stops only when i equals 50
```

### Trace Execution Path
```
1. set_breakpoint with log_message at key locations
2. set suspend_policy to "none" for all breakpoints
3. start_debug_session
4. resume_execution
5. Check IDE console for trace output
```

## Error Handling

A failing tool returns a **successful** JSON-RPC result whose payload carries `isError: true` and
a human-readable message in `content[0].text` — never a JSON-RPC `error` object. That is
deliberate: the model can read the message and act on it, whereas a protocol error surfaces to the
user as a hard transport failure.

Most messages are free-form prose, and the exact strings are pinned by the test suite because they
are the only failure signal a client gets. Representative examples:

- `No active debug session`
- `Session not found: <id>`
- `Session must be paused to <verb>`
- `Missing required parameter: <name>`
- `File not found: <path>. For files inside JAR archives, use the '!/' separator ...`
- `Cannot set breakpoint at <path>:<line> (not a valid breakpoint location)`

**Project resolution** is the one place that returns a structured payload — a JSON object with
`error`, `message` and `available_projects` keys, where `error` is one of:

- `no_project_open` - No project is open in the IDE
- `project_not_found` - No open project matches the supplied `project_path`
- `multiple_projects_open` - Several projects are open and `project_path` was omitted

> Earlier revisions of this file also listed `session_not_found`, `session_not_paused`,
> `breakpoint_not_found`, `invalid_location` and `evaluation_error` as structured error codes. No
> tool has ever emitted them. Do not write agent logic that branches on those strings.

## Language-Specific Limitations

Some debugging features depend on the underlying debugger's capabilities:

### Full Support (Java, Kotlin, Python, JavaScript, TypeScript, PHP, Ruby)
- ✅ All tools work as expected
- ✅ Expression evaluation with method calls
- ✅ Variable modification for all types
- ✅ Rich type inspection

### Limited Support (Rust, C++, C, Go, Swift)
These languages use native debuggers (LLDB/GDB) with some limitations:

| Tool | Limitation |
|------|------------|
| `evaluate_expression` | Variable inspection works. Method calls (e.g., `s.len()`, `vec.size()`) may fail with "no field named X" errors. |
| `set_variable` | Works for primitive types (int, float, bool). Complex types (String, Vec, structs) may fail with "could not find item" errors. |

**Workarounds for native languages:**
- Use `get_variables` to inspect values instead of `evaluate_expression` with method calls
- For Rust strings, access the underlying data: evaluate `s` instead of `s.len()`
- Focus on stepping and breakpoints for debugging flow

## Requirements

- JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, RustRover, CLion, GoLand, etc.)
- IDE must have an open project with a debuggable run configuration
- This plugin must be installed and enabled

---

## Developer Guide: Testing

```bash
./gradlew test    # whole suite, platform tests included — ~40s headless
```

Run it before pushing. There is no separate "fast tier": the suite is small enough that splitting
it would cost more than it saves.

### How the suite is organised

| Layer | Location | What it protects |
|-------|----------|------------------|
| **Golden contracts** | `contract/` | The client-facing surface: 23 tool schemas, 31 result-model wire shapes |
| **Transport conformance** | `server/transport/` | Every route, status code, header, Origin decision and SSE frame, over real HTTP |
| **Tool behaviour** | `tools/**/*BehaviorTest` | What a tool actually does to IDE state |
| **Unit** | everything else | Pure logic — log-message transforms, safety analysis, value presentation |

### Golden contract files

`src/test/resources/contract/` holds two snapshots that exist to make a large refactor safe:

- `tool-manifest.txt` — every tool's name, description, input schema, output schema and annotations
- `result-shapes.txt` — every result model's wire keys, JSON kinds, nullability and optionality

They are a **contract with MCP clients**. Result models use plain Kotlin property names as wire
keys — there is exactly one `@SerialName` in `src/main` — so an IDE "Rename" on a result property
is a source-compatible change that silently breaks every client. The snapshot is what catches it.

Changing them is sometimes correct, but must always be deliberate:

```bash
./gradlew test --tests "*ToolManifestContractTest" -Dcontract.update=true
```

Review the resulting diff as part of the change, and treat it as the list of breaking changes the
release notes owe clients.

### Writing transport or tool-behaviour tests

Extend `McpHttpTestCase`. It boots the real `KtorMcpServer` on a free port and drives it with the
JDK's `java.net.http.HttpClient` (no Ktor client dependency needed).

**Wrap any tool call that mutates IDE state in `pumpingEdt { ... }`.** `BasePlatformTestCase` runs
test methods *on the EDT*, and tools like `set_breakpoint` hop onto the EDT themselves — so a plain
blocking HTTP call deadlocks, surfacing as a misleading 30-second `HttpTimeoutException`.

Write fixture files to disk under `project.basePath`, not via `myFixture.addFileToProject`:
`VirtualFileResolver` resolves through `LocalFileSystem`, which cannot see the in-memory
`TempFileSystem`.

### Known gaps — green does not mean covered

Stated plainly so nobody mistakes the suite for more than it is:

- **No live debug session.** Nothing starts a real debuggee, so the paused-state paths of
  `get_variables`, `evaluate_expression`, `step_*`, `get_stack_trace` and `wait_for_pause` are
  covered only for their error branches and their utility layers. The three breakpoint tools are
  the only ones with real success-path behaviour coverage.
- **`SessionStatusCollector` reports a degraded view**, and the tests pin that rather than the
  documented ideal: `stackSummary` returns at most the current frame regardless of
  `max_stack_frames`, `totalStackDepth` is 1 or 0, `currentThread` is hardcoded to `main`,
  `threadCount` to 1, and `BreakpointHitInfo.hitCount` is always 0.
- **Pause-reason detection is a file/line heuristic.** It returns only `breakpoint` or `step`,
  never `exception` or `pause`, though the output schema advertises all four.
- **The evaluate-expression guard has known bypasses**, none of them fixed here: interpolated
  string templates (Kotlin `"${...}"`, JS backticks, Python f-strings) are blanked before scanning;
  an unbalanced quote blanks everything after it; only the first 10,000 characters are scanned; and
  the blocklist is JVM-specific, so `os.system`, `require('child_process')` and similar pass
  unblocked. `set_variable` and `evaluate_expression` both consult the guard —
  `SafetyGuardCoverageTest` fails if a third evaluating tool appears without one.
- **Breakpoint conditions and log messages** are evaluated by the debugger without passing through
  the guard.
- **`log_message` falls back to Java syntax** for Rust, Go, Swift, C and C++, producing expressions
  those debuggers cannot evaluate.
- **`set_breakpoint` contains a hardcoded `delay(100)`** after the async toggle. Under load it can
  report "Failed to create breakpoint" for one that does get created.
- **UI is barely covered.** `McpToolWindowPanel` eagerly resolves `McpServerService` and does not
  survive the light fixture, so `ToolWindowActionsTest` checks `RefreshAction` at the source level
  rather than by driving a real tool window.

---

## Developer Guide: MCP Structured Outputs

When developing tools for this plugin, be aware of the MCP protocol's structured output requirements.

### When `outputSchema` is Defined

If a tool defines an `outputSchema`, the MCP protocol **requires** the response to include a `structuredContent` field containing the actual JSON object (not just text content).

**Error if missing:**
```
MCP error -32600: Tool [name] has an output schema but did not return structured content
```

**Solution:** Use `createJsonResult()` from `AbstractMcpTool` - it automatically populates both `content` (text) and `structuredContent` (JSON object).

### Nullable Fields in Output Schema

When a field can be `null`, the JSON Schema must explicitly allow it using an array of types:

```kotlin
// Wrong - will fail validation if value is null:
putJsonObject("className") { put("type", "string") }

// Correct - allows null values:
putJsonObject("className") {
    putJsonArray("type") {
        add(JsonPrimitive("string"))
        add(JsonPrimitive("null"))
    }
}
```

### Common Nullable Fields

These fields are commonly null and should use `["type", "null"]`:
- `className` - may be null for synthetic or library frames
- `methodName` - may be null for lambda expressions
- `file` - may be null for generated code
- `line` - may be null when source mapping unavailable
- `pausedReason` - null when session is running
- `currentLocation` - null when session is not paused

### Tool Result Pattern

```kotlin
// For tools WITH outputSchema - uses structuredContent:
override val outputSchema: JsonObject = buildJsonObject { /* schema */ }

override suspend fun doExecute(...): ToolCallResult {
    return createJsonResult(MyResultData(...))  // Auto-populates structuredContent
}

// For tools WITHOUT outputSchema - simpler text response:
override suspend fun doExecute(...): ToolCallResult {
    return createSuccessResult("Operation completed")
}
```

### Required Imports for Nullable Schemas

```kotlin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
```
