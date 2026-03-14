---
name: jetbrains-debugger
description: >-
  Guide for using JetBrains IDE Debugger MCP tools to programmatically debug applications.
  TRIGGER when ANY of these MCP tools are available: list_run_configurations, execute_run_configuration,
  start_debug_session, stop_debug_session, get_debug_session_status, list_debug_sessions,
  set_breakpoint, remove_breakpoint, list_breakpoints, resume_execution, pause_execution,
  step_over, step_into, step_out, run_to_line, get_stack_trace, select_stack_frame,
  list_threads, get_variables, set_variable, get_source_context, evaluate_expression.
  Use when debugging any application, investigating bugs, tracing execution flow, inspecting
  runtime state, or when the user says "debug", "breakpoint", "step through", "inspect variable",
  "why is this returning X", "trace execution", or similar debugging-related requests.
  PREFER the debugger over reading code and guessing when runtime behavior is unclear.
---

# JetBrains Debugger MCP

Use these tools to **actually debug** applications in a JetBrains IDE rather than guessing from static code.

**Complete parameter reference:** See [references/tool-reference.md](references/tool-reference.md) for all tool parameters, types, defaults, and return schemas.

## When to Use the Debugger

**USE the debugger when:**
- A bug involves runtime state (wrong values, unexpected nulls, incorrect flow)
- Reading code alone doesn't explain the behavior
- The user asks "why does X happen" or "what value does Y have"
- A test fails and the cause isn't obvious from the assertion message
- You need to verify a hypothesis about execution flow
- The user explicitly asks to debug

**DON'T use the debugger when:**
- The bug is a clear syntax error, typo, or missing import
- The fix is obvious from reading the code (e.g., off-by-one, wrong operator)
- There's no run configuration available to debug

## Core Workflow

### Standard Debugging Sequence

```
1. list_run_configurations          -- Find a config with can_debug: true
2. set_breakpoint                   -- Set breakpoint(s) BEFORE starting
3. start_debug_session              -- Launch the debugger
4. get_debug_session_status         -- Check if session started (state: "running")
5. [wait for breakpoint hit]        -- Call get_debug_session_status to poll
6. get_debug_session_status         -- When paused: see variables, stack, source in ONE call
7. evaluate_expression              -- Test hypotheses about values
8. step_over / step_into / step_out -- Navigate through code
9. get_debug_session_status         -- Inspect state after each step
10. resume_execution                -- Continue to next breakpoint or end
11. stop_debug_session              -- Clean up when done
```

### Critical Rules

1. **Set breakpoints BEFORE starting the session.** Breakpoints can be set without an active session. Setting them first ensures the program pauses where you need it.

2. **Always use `get_debug_session_status` as your primary inspection tool.** It returns variables, stack trace, source context, and current location in ONE call. Do NOT call `get_variables`, `get_stack_trace`, and `get_source_context` separately unless you need specific parameters (e.g., a different frame index or more context lines).

3. **After `resume_execution` or any step command, poll `get_debug_session_status`** to check when the session has paused again. The step/resume commands return immediately with `newState: "running"` - they do NOT wait for the program to pause.

4. **Line numbers are 1-based.** When setting breakpoints or using `run_to_line`, use the line numbers as they appear in the editor (starting from 1).

5. **File paths must be absolute.** For `set_breakpoint`, `run_to_line`, and `get_source_context`, always use absolute file paths (e.g., `/Users/dev/project/src/Main.java`).

6. **`session_id` is optional for single-session debugging.** When only one debug session exists, all tools auto-select it. Only specify `session_id` when multiple sessions are active.

7. **`project_path` is required when multiple projects are open.** If omitted with multiple projects, tools return an error listing available projects.

## Debugging Patterns

### Pattern: Find Why a Value is Wrong
```
1. set_breakpoint at the line where the wrong value is used
2. start_debug_session with the appropriate run configuration
3. get_debug_session_status (poll until state: "paused")
4. Inspect variables in the response -- the wrong value and its inputs are visible
5. evaluate_expression to test alternative calculations
6. If the value was already wrong here, set_breakpoint earlier in the call chain
7. resume_execution and repeat
```

### Pattern: Debug a Specific Loop Iteration
```
1. set_breakpoint with condition (e.g., condition: "i == 50")
2. start_debug_session
3. resume_execution -- debugger runs at full speed until condition is true
4. get_debug_session_status -- inspect state at exactly iteration 50
```

### Pattern: Trace Execution Without Stopping
```
1. set_breakpoint with log_message and suspend_policy: "none"
   Example: log_message: "Entering process() with id={id}, count={items.size()}"
2. start_debug_session
3. resume_execution
4. Check IDE console output for trace log -- execution never pauses
```

### Pattern: Inspect a Different Stack Frame
```
1. get_debug_session_status -- see the stack summary
2. select_stack_frame with the frame_index of interest (0 = current, 1 = caller, etc.)
3. get_variables -- now shows variables from the selected frame
4. evaluate_expression -- expressions evaluated in the selected frame's context
```

### Pattern: Test a Fix Without Restarting
```
1. Pause at the point of interest
2. evaluate_expression with the corrected logic to verify it produces the right result
3. set_variable to inject the correct value
4. resume_execution to see if the fix resolves the downstream issue
```

## Common Mistakes to Avoid

| Mistake | Correct Approach |
|---------|-----------------|
| Calling `get_variables` + `get_stack_trace` + `get_source_context` separately | Use `get_debug_session_status` -- returns all three in one call |
| Starting debug session without setting breakpoints first | Set breakpoints BEFORE `start_debug_session` |
| Assuming `step_over` returns the new state | Call `get_debug_session_status` after stepping to see where you are |
| Using 0-based line numbers | Line numbers are **1-based** (as shown in the editor) |
| Using relative file paths | Always use **absolute** file paths |
| Not polling after `resume_execution` | The program may take time to hit the next breakpoint -- poll `get_debug_session_status` |
| Calling `evaluate_expression` with method calls in Rust/C++/Go | Use `get_variables` for native languages; method calls may fail in LLDB/GDB |
| Guessing variable values from source code | Use the debugger to inspect actual runtime values |
| Forgetting to `stop_debug_session` when done | Always clean up debug sessions |

## Language-Specific Notes

### Full Support (Java, Kotlin, Python, JavaScript, TypeScript, PHP, Ruby)
- All tools work as documented
- `evaluate_expression` supports method calls, field access, arithmetic
- `set_variable` works for all types including objects and strings

### Limited Support (Rust, C++, C, Go, Swift)
These use native debuggers (LLDB/GDB) with restrictions:
- `evaluate_expression`: Variable inspection works, but method calls (e.g., `s.len()`, `vec.size()`) may fail
- `set_variable`: Works for primitives (int, float, bool). Complex types (String, Vec, structs) may fail
- **Workaround:** Use `get_variables` to inspect values instead of `evaluate_expression` with method calls

## Tool Quick Reference

| Tool | Purpose | Requires Paused |
|------|---------|:---:|
| `list_run_configurations` | Find debuggable configurations | No |
| `execute_run_configuration` | Run or debug a configuration | No |
| `start_debug_session` | Start debugging | No |
| `stop_debug_session` | End debugging | No |
| `list_debug_sessions` | See active sessions | No |
| `get_debug_session_status` | **Primary inspector** -- variables, stack, source, location | No (but most useful when paused) |
| `set_breakpoint` | Set line breakpoint (with optional condition/log) | No |
| `remove_breakpoint` | Remove a breakpoint | No |
| `list_breakpoints` | See all breakpoints | No |
| `resume_execution` | Continue running | **Yes** |
| `pause_execution` | Pause running program | No (must be running) |
| `step_over` | Next line (skip into functions) | **Yes** |
| `step_into` | Enter function call | **Yes** |
| `step_out` | Finish current function | **Yes** |
| `run_to_line` | Run to specific line | **Yes** |
| `get_stack_trace` | Full call stack | **Yes** |
| `select_stack_frame` | Change frame context | **Yes** |
| `list_threads` | See all threads | **Yes** |
| `get_variables` | Variables in current frame | **Yes** |
| `set_variable` | Modify a variable at runtime | **Yes** |
| `get_source_context` | Source code around a location | No |
| `evaluate_expression` | Evaluate any expression | **Yes** |
