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
4. **Resume and wait**: `resume_execution` to run until a breakpoint is hit
5. **Inspect state**: `get_debug_session_status` returns variables, stack trace, and source context in one call
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

### Evaluate Before Modifying
Before using `set_variable`, use `evaluate_expression` to preview the change:
```json
{
  "expression": "calculateNewValue(currentValue)",
  "allow_side_effects": false
}
```

### Handle Multiple Projects
When multiple projects are open in the IDE, always specify `projectPath`:
```json
{
  "projectPath": "/Users/dev/my-project",
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

Tools return structured errors:
- `session_not_found` - Invalid session_id
- `session_not_paused` - Operation requires paused session
- `breakpoint_not_found` - Invalid breakpoint_id
- `invalid_location` - Cannot set breakpoint at location
- `multiple_projects_open` - Must specify projectPath
- `evaluation_error` - Expression evaluation failed

## Requirements

- JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, etc.)
- IDE must have an open project with a debuggable run configuration
- This plugin must be installed and enabled

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
