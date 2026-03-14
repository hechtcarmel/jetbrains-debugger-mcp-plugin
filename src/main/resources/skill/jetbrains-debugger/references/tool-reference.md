# JetBrains Debugger MCP - Complete Tool Reference

## Table of Contents
- [Session Management](#session-management)
- [Breakpoints](#breakpoints)
- [Execution Control](#execution-control)
- [Stack & Threads](#stack--threads)
- [Variables](#variables)
- [Navigation](#navigation)
- [Evaluation](#evaluation)

---

## Session Management

### `list_run_configurations`
List available run/debug configurations in the project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path (required if multiple projects open) |

**Returns:** `configurations[]` (name, type, canRun, canDebug, isTemporary, folder), `activeConfiguration`

### `execute_run_configuration`
Execute a run configuration in debug or run mode.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `project_path` | string | No | | Project path |
| `name` | string | **Yes** | | Configuration name (exact match) |
| `mode` | string | No | `"debug"` | `"debug"` or `"run"` |

**Returns:** `status`, `configurationName`, `mode`, `message`

### `list_debug_sessions`
List all active debug sessions.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path |

**Returns:** `sessions[]` (id, name, state, isCurrent, runConfigurationName), `currentSessionId`, `totalCount`

### `start_debug_session`
Start a new debug session for a run configuration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path |
| `configuration_name` | string | **Yes** | Run configuration name |

**Returns:** `status` ("started"/"starting"), `session` (DebugSessionInfo), `message`

**Note:** Polls for up to 5 seconds for the session to start. The session may still be in "starting" state.

### `stop_debug_session`
Stop/terminate a debug session.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID (uses current if omitted) |
| `project_path` | string | No | Project path |

**Returns:** `status` ("stopped"), `sessionId`, `message`

### `get_debug_session_status` (PRIMARY INSPECTION TOOL)
Get comprehensive session state in a single call. **Use this as the first inspection tool when paused.**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `session_id` | string | No | | Session ID |
| `include_variables` | boolean | No | `true` | Include variables in scope |
| `include_source_context` | boolean | No | `true` | Include surrounding source code |
| `source_context_lines` | integer | No | `5` | Lines of source context (0-50) |
| `max_stack_frames` | integer | No | `10` | Max stack frames (1-200) |
| `project_path` | string | No | | Project path |

**Returns:**
- `sessionId`, `name`, `state` ("running", "paused", "stopped")
- `pausedReason` ("breakpoint", "step", "exception", "pause") - null if running
- `currentLocation` (file, line, className, methodName) - null if not paused
- `breakpointHit` (breakpoint info) - null if not hit
- `stackSummary[]` (index, file, line, className, methodName)
- `variables[]` (name, value, type, hasChildren)
- `sourceContext` (file, lines[], currentLine, breakpointsInView[])
- `currentThread` (id, name, state)
- `threadCount`

---

## Breakpoints

### `list_breakpoints`
List all breakpoints in the project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path |

**Returns:** `breakpoints[]` (id, type, file, line, enabled, condition, logMessage, suspendPolicy, hitCount, temporary), `totalCount`, `enabledCount`

### `set_breakpoint`
Set a line breakpoint with optional conditions or log messages.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `project_path` | string | No | | Project path |
| `file_path` | string | **Yes** | | **Absolute** file path |
| `line` | integer | **Yes** | | 1-based line number (min: 1) |
| `condition` | string | No | | Boolean expression (e.g., `"count > 10"`) |
| `log_message` | string | No | | Message with `{expression}` placeholders |
| `suspend_policy` | string | No | `"all"` | `"all"`, `"thread"`, or `"none"` |
| `enabled` | boolean | No | `true` | Whether breakpoint is active |
| `temporary` | boolean | No | `false` | Remove after first hit |

**Returns:** `breakpointId`, `status` ("set"), `verified`, `file`, `line`, `message`

**Log message syntax:** Use `{expression}` placeholders. Auto-transformed per language:
- Java: `"x={x}"` becomes `"x=" + (x)`
- Kotlin: `"x={x}"` becomes `"x=$x"`
- Python: `"x={x}"` becomes `f"x={x}"`
- JS/TS: `"x={x}"` becomes `` `x=${x}` ``

**Tracepoint:** Set `suspend_policy: "none"` with a `log_message` to log without stopping.

### `remove_breakpoint`
Remove a breakpoint by its ID.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path |
| `breakpoint_id` | string | **Yes** | Breakpoint ID from `list_breakpoints` or `set_breakpoint` |

**Returns:** `breakpointId`, `status` ("removed"), `message`

---

## Execution Control

All stepping/resume tools require the session to be **paused**. They return `ExecutionControlResult` with `sessionId`, `action`, `status`, `newState`, `message`.

### `resume_execution`
Resume program execution from paused state.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session paused. **New state:** "running"

### `pause_execution`
Pause a running debug session.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session running. **New state:** "paused"

### `step_over`
Execute current line, stepping over function calls.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session paused. **New state:** "running" (will pause at next line)

### `step_into`
Step into the function call on current line.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session paused. **New state:** "running" (will pause inside function)

### `step_out`
Continue execution until current function returns.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session paused. **New state:** "running" (will pause at caller)

### `run_to_line`
Continue execution until a specific line is reached.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `file_path` | string | **Yes** | Absolute file path |
| `line` | integer | **Yes** | 1-based target line (min: 1) |
| `project_path` | string | No | Project path |

**Requires:** Session paused. **New state:** "running" (will pause at target line)

---

## Stack & Threads

### `get_stack_trace`
Get the call stack showing how execution reached current point.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `session_id` | string | No | | Session ID |
| `max_frames` | integer | No | `50` | Max frames to return (1-200) |
| `project_path` | string | No | | Project path |

**Requires:** Session paused.

**Returns:** `sessionId`, `threadId`, `frames[]` (index, file, line, className, methodName, isCurrent, isLibrary, presentation), `totalFrames`

### `select_stack_frame`
Change debugger context to a different stack frame (to inspect variables in a different call).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `frame_index` | integer | **Yes** | 0-based frame index (0 = topmost/current) |
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session paused.

**Returns:** `frameIndex`, `frame` (StackFrameInfo), `message`

### `list_threads`
List all threads in the debugged process.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Requires:** Session paused.

**Returns:** `sessionId`, `threads[]` (id, name, state, isCurrent), `currentThreadId`

---

## Variables

### `get_variables`
Get all variables visible in current or specified stack frame.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `session_id` | string | No | | Session ID |
| `frame_index` | integer | No | `0` | Stack frame index (0 = current) |
| `project_path` | string | No | | Project path |

**Requires:** Session paused.

**Returns:** `sessionId`, `frameIndex`, `variables[]` (name, value, type, hasChildren)

### `set_variable`
Modify a variable's value at runtime.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `variable_name` | string | **Yes** | Variable name |
| `new_value` | string | **Yes** | New value expression (e.g., `"42"`, `"\"hello\""`, `"null"`) |
| `project_path` | string | No | Project path |

**Requires:** Session paused.

**Returns:** `sessionId`, `variableName`, `oldValue`, `newValue`, `type`, `message`

**Limitation:** In native debuggers (Rust, C++, Go), complex types may fail. Primitives work reliably.

---

## Navigation

### `get_source_context`
Get source code lines around a location.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `session_id` | string | No | | Session ID |
| `file_path` | string | No | | Absolute path (uses current location if omitted) |
| `line` | integer | No | | 1-based line (uses current if omitted) |
| `lines_before` | integer | No | `5` | Context lines before |
| `lines_after` | integer | No | `5` | Context lines after |
| `project_path` | string | No | | Project path |

**Returns:** `file`, `startLine`, `endLine`, `currentLine`, `lines[]` (number, content, isCurrent), `breakpointsInView[]`

---

## Evaluation

### `evaluate_expression`
Evaluate an expression in the current debug context.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `expression` | string | **Yes** | | Expression to evaluate (e.g., `"x"`, `"list.size()"`, `"a + b"`) |
| `session_id` | string | No | | Session ID |
| `frame_index` | integer | No | `0` | Stack frame index |
| `project_path` | string | No | | Project path |

**Requires:** Session paused.

**Returns:** `sessionId`, `frameIndex`, `result` (expression, value, type, hasChildren, error)

**Limitations for native languages (Rust, C++, Go, Swift):**
- Variable inspection works
- Method calls (e.g., `s.len()`, `vec.size()`) may fail
- Use `get_variables` as an alternative
