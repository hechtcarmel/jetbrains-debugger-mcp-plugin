# Debugger MCP Server - Tool Reference

This document provides detailed documentation for all 22 MCP tools available in the JetBrains Debugger MCP Server plugin.

## Tool Overview

Tools are organized into categories based on functionality:

### Run Configuration Tools (5)

| Tool | Description |
|------|-------------|
| `list_run_configurations` | List all available run configurations |
| `list_run_sessions` | List all active run sessions |
| `stop_run_session` | Stop a run session |
| `execute_run_configuration` | Execute a run configuration in run or debug mode |
| `get_run_log` | Get console output from a run session |

### Debug Session Tools (4)

| Tool | Description |
|------|-------------|
| `list_debug_sessions` | List all active debug sessions |
| `start_debug_session` | Start a new debug session |
| `stop_debug_session` | Stop a debug session |
| `get_debug_session_status` | Get comprehensive session status |

### Breakpoint Tools (3)

| Tool | Description |
|------|-------------|
| `list_breakpoints` | List all breakpoints |
| `set_breakpoint` | Set a line breakpoint |
| `remove_breakpoint` | Remove a breakpoint |

### Execution Control Tools (6)

| Tool | Description |
|------|-------------|
| `resume_execution` | Resume execution from paused state |
| `pause_execution` | Pause execution at current point |
| `step_over` | Step over to next line |
| `step_into` | Step into method call |
| `step_out` | Step out of current method |
| `run_to_line` | Run to specific line |

### Stack & Thread Tools (3)

| Tool | Description |
|------|-------------|
| `get_stack_trace` | Get call stack |
| `select_stack_frame` | Select a stack frame |
| `list_threads` | List all threads |

### Variable Tools (2)

| Tool | Description |
|------|-------------|
| `get_variables` | Get frame variables |
| `set_variable` | Modify variable value |

### Navigation Tools (1)

| Tool | Description |
|------|-------------|
| `get_source_context` | Get source code context |

### Evaluation Tools (1)

| Tool | Description |
|------|-------------|
| `evaluate_expression` | Evaluate expression in debug context |

---

## Table of Contents

- [Common Parameters](#common-parameters)
- [Run Configuration Tools](#run-configuration-tools)
  - [list_run_configurations](#list_run_configurations)
  - [list_run_sessions](#list_run_sessions)
  - [stop_run_session](#stop_run_session)
  - [execute_run_configuration](#execute_run_configuration)
  - [get_run_log](#get_run_log)
- [Debug Session Tools](#debug-session-tools)
  - [list_debug_sessions](#list_debug_sessions)
  - [start_debug_session](#start_debug_session)
  - [stop_debug_session](#stop_debug_session)
  - [get_debug_session_status](#get_debug_session_status)
- [Breakpoint Tools](#breakpoint-tools)
  - [list_breakpoints](#list_breakpoints)
  - [set_breakpoint](#set_breakpoint)
  - [remove_breakpoint](#remove_breakpoint)
- [Execution Control Tools](#execution-control-tools)
  - [resume_execution](#resume_execution)
  - [pause_execution](#pause_execution)
  - [step_over](#step_over)
  - [step_into](#step_into)
  - [step_out](#step_out)
  - [run_to_line](#run_to_line)
- [Stack & Thread Tools](#stack--thread-tools)
  - [get_stack_trace](#get_stack_trace)
  - [select_stack_frame](#select_stack_frame)
  - [list_threads](#list_threads)
- [Variable Tools](#variable-tools)
  - [get_variables](#get_variables)
  - [set_variable](#set_variable)
- [Navigation Tools](#navigation-tools)
  - [get_source_context](#get_source_context)
- [Evaluation Tools](#evaluation-tools)
  - [evaluate_expression](#evaluate_expression)
- [Error Handling](#error-handling)

---

## Common Parameters

All tools accept an optional `project_path` parameter:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Absolute path to the project root. Required when multiple projects are open in the IDE. |

### Session Parameters

Many tools operate on debug sessions and accept:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | ID of the debug session. If omitted, uses the current/active session. |

---

## Run Configuration Tools

### list_run_configurations

Lists all run configurations available in the project.

**Use when:**
- Finding available run configurations to start debugging
- Discovering test configurations to run

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path (required if multiple projects open) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "list_run_configurations",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "configurations": [
    {
      "name": "Application",
      "type": "Application",
      "canDebug": true
    },
    {
      "name": "All Tests",
      "type": "JUnit",
      "canDebug": true
    },
    {
      "name": "UserServiceTest",
      "type": "JUnit",
      "canDebug": true
    }
  ],
  "count": 3
}
```

---

### list_run_sessions

Lists all active run sessions (run-only processes, not debug sessions).

**Use when:**
- Monitoring active run processes
- Checking which run configurations are currently executing
- Finding process IDs for running applications

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "list_run_sessions",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "sessions": [
    {
      "id": "run_session_12345",
      "name": "Application",
      "state": "running",
      "processId": 12345,
      "executorId": "Run",
      "runConfigurationName": "Application"
    },
    {
      "id": "run_session_67890",
      "name": "Build Project",
      "state": "running",
      "processId": 67890,
      "executorId": "Build",
      "runConfigurationName": "Build Project"
    }
  ],
  "totalCount": 2
}
```

**Session State Values:**
- `running` - Process is actively executing
- `stopped` - Process has terminated

---

### execute_run_configuration

Executes a run configuration in either 'run' or 'debug' mode.

**Use when:**
- Starting the application in debug mode
- Running tests to hit breakpoints
- Running without debugging when you don't need breakpoints

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Name of the run configuration |
| `mode` | string | No | `debug` (default) or `run` |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "execute_run_configuration",
    "arguments": {
      "name": "UserServiceTest",
      "mode": "debug"
    }
  }
}
```

**Example Response:**

```json
{
  "status": "started",
  "configurationName": "UserServiceTest",
  "mode": "debug",
  "message": "Started 'UserServiceTest' in debug mode"
}
```

---

### stop_run_session

Stops/terminates a run session.

**Use when:**
- Ending a run session
- Stopping a running application
- Cleaning up after running tests

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to stop (uses first available if omitted) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "stop_run_session",
    "arguments": {
      "session_id": "run_session_12345"
    }
  }
}
```

**Example Response:**

```json
{
  "status": "stopped",
  "sessionId": "run_session_12345",
  "message": "Run session stopped"
}
```

**Note:** If no `session_id` is provided, the tool will stop the first available run session.

---

### get_run_log

Retrieves the console output from a run session by its session ID. Returns the last N lines of the log.

**Use when:**
- Viewing console output from a running application
- Checking test results and output
- Debugging application output without breaking into the debugger
- Monitoring long-running processes

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | Yes | Session ID from `list_run_sessions` |
| `lines` | integer | No | Number of lines from the end to return (default: 100, max: 10000) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "get_run_log",
    "arguments": {
      "session_id": "run_session_12345",
      "lines": 50
    }
  }
}
```

**Example Response:**

```json
{
  "sessionId": "12345",
  "log": "2024-01-15 10:30:15.123 [main] INFO  Application - Starting application...\n2024-01-15 10:30:15.456 [main] INFO  Application - Configuration loaded\n2024-01-15 10:30:16.789 [main] INFO  Application - Server started on port 8080\n",
  "totalLines": 150,
  "returnedLines": 50
}
```

**Error Response (session not found):**

```json
{
  "error": "Run session not found: run_session_12345"
}
```

**Note:** The `log` field contains plain text with newline characters (`\n`). Parse this appropriately in your client. If the log is empty, the session may not have produced any output yet, or may have already terminated.

---

## Debug Session Tools

### list_debug_sessions

Lists all active debug sessions.

**Use when:**
- Finding active debug sessions
- Checking which sessions are running or paused

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "list_debug_sessions",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "sessions": [
    {
      "id": "session_12345",
      "name": "UserServiceTest",
      "state": "paused",
      "isCurrent": true
    }
  ],
  "count": 1
}
```

**State Values:**
- `running` - Executing code
- `paused` - Stopped at breakpoint or step
- `stopped` - Session terminated

---

### start_debug_session

Starts a new debug session for a run configuration.

**Use when:**
- Beginning a debugging session
- Launching app to hit set breakpoints

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `configuration_name` | string | Yes | Name of the run configuration to debug |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "start_debug_session",
    "arguments": {
      "configuration_name": "Application"
    }
  }
}
```

**Example Response:**

```json
{
  "sessionId": "session_12345",
  "name": "Application",
  "state": "running",
  "message": "Debug session started for 'Application'"
}
```

---

### stop_debug_session

Stops/terminates a debug session.

**Use when:**
- Ending a debugging session
- Stopping the application

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to stop (uses current if omitted) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "stop_debug_session",
    "arguments": {
      "session_id": "session_12345"
    }
  }
}
```

**Example Response:**

```json
{
  "status": "stopped",
  "sessionId": "session_12345",
  "message": "Debug session stopped"
}
```

---

### get_debug_session_status

> **Primary Debugging Tool** - Use this to understand the current debug state.

Gets comprehensive status of a debug session including variables, stack, and source context.

**Use when:**
- After hitting a breakpoint to see current state
- After stepping to see what changed
- Getting a complete picture of debug state in one call

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID (uses current if omitted) |
| `include_variables` | boolean | No | Include variables (default: true) |
| `include_source_context` | boolean | No | Include source code (default: true) |
| `source_context_lines` | integer | No | Lines above/below current line (default: 5) |
| `max_stack_frames` | integer | No | Max stack frames to return (default: 10) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "get_debug_session_status",
    "arguments": {
      "include_variables": true,
      "source_context_lines": 5
    }
  }
}
```

**Example Response:**

```json
{
  "sessionId": "session_12345",
  "name": "UserServiceTest",
  "state": "paused",
  "pausedReason": "breakpoint",
  "currentLocation": {
    "file": "/Users/dev/project/src/UserService.java",
    "line": 42,
    "className": "com.example.UserService",
    "methodName": "findUser"
  },
  "breakpointHit": {
    "breakpointId": "bp_123",
    "type": "line",
    "file": "/Users/dev/project/src/UserService.java",
    "line": 42
  },
  "stackSummary": [
    {
      "index": 0,
      "file": "/Users/dev/project/src/UserService.java",
      "line": 42,
      "className": "com.example.UserService",
      "methodName": "findUser",
      "isCurrent": true
    }
  ],
  "variables": [
    {
      "name": "userId",
      "value": "\"user-123\"",
      "type": "String",
      "hasChildren": false
    },
    {
      "name": "this",
      "value": "UserService@1234",
      "type": "com.example.UserService",
      "hasChildren": true
    }
  ],
  "sourceContext": {
    "file": "/Users/dev/project/src/UserService.java",
    "startLine": 37,
    "endLine": 47,
    "currentLine": 42,
    "lines": [
      { "number": 37, "content": "    public User findUser(String userId) {", "isCurrent": false },
      { "number": 38, "content": "        logger.info(\"Finding user: {}\", userId);", "isCurrent": false },
      { "number": 39, "content": "", "isCurrent": false },
      { "number": 40, "content": "        User user = userRepository.findById(userId);", "isCurrent": false },
      { "number": 41, "content": "", "isCurrent": false },
      { "number": 42, "content": "        if (user == null) {", "isCurrent": true },
      { "number": 43, "content": "            throw new UserNotFoundException(userId);", "isCurrent": false },
      { "number": 44, "content": "        }", "isCurrent": false },
      { "number": 45, "content": "", "isCurrent": false },
      { "number": 46, "content": "        return user;", "isCurrent": false },
      { "number": 47, "content": "    }", "isCurrent": false }
    ],
    "breakpointsInView": [42]
  },
  "currentThread": {
    "id": "main",
    "name": "main",
    "state": "paused",
    "isCurrent": true
  }
}
```

---

## Breakpoint Tools

### list_breakpoints

Lists all breakpoints in the project.

**Use when:**
- Reviewing existing breakpoints
- Finding breakpoints to remove

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | No | Filter by file path |
| `type` | string | No | Filter by type: `line`, `exception` |
| `enabled` | boolean | No | Filter by enabled state |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "list_breakpoints",
    "arguments": {
      "file_path": "/Users/dev/project/src/UserService.java"
    }
  }
}
```

**Example Response:**

```json
{
  "breakpoints": [
    {
      "id": "bp_123",
      "type": "line",
      "file": "/Users/dev/project/src/UserService.java",
      "line": 42,
      "enabled": true,
      "condition": null,
      "logMessage": null,
      "suspendPolicy": "all"
    },
    {
      "id": "bp_456",
      "type": "line",
      "file": "/Users/dev/project/src/UserService.java",
      "line": 58,
      "enabled": true,
      "condition": "user != null",
      "logMessage": null,
      "suspendPolicy": "all"
    }
  ],
  "count": 2
}
```

---

### set_breakpoint

Sets a line breakpoint at the specified location.

**Use when:**
- Setting a new breakpoint
- Creating conditional breakpoints
- Creating tracepoints (log without pause)

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | Yes | Absolute path to the file |
| `line` | integer | Yes | 1-based line number |
| `condition` | string | No | Conditional expression (only pause when true) |
| `log_message` | string | No | Log message (use `{expr}` for evaluation) |
| `suspend_policy` | string | No | `all`, `thread`, or `none` (default: `all`) |
| `enabled` | boolean | No | Whether enabled (default: true) |
| `temporary` | boolean | No | Remove after first hit (default: false) |
| `project_path` | string | No | Project path |

**Example Request (simple):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "set_breakpoint",
    "arguments": {
      "file_path": "/Users/dev/project/src/UserService.java",
      "line": 42
    }
  }
}
```

**Example Request (conditional):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "set_breakpoint",
    "arguments": {
      "file_path": "/Users/dev/project/src/UserService.java",
      "line": 42,
      "condition": "userId.equals(\"admin\")"
    }
  }
}
```

**Example Request (tracepoint):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "set_breakpoint",
    "arguments": {
      "file_path": "/Users/dev/project/src/UserService.java",
      "line": 42,
      "log_message": "findUser called with: {userId}",
      "suspend_policy": "none"
    }
  }
}
```

**Example Response:**

```json
{
  "breakpointId": "bp_789",
  "status": "set",
  "verified": true,
  "message": "Breakpoint set at UserService.java:42",
  "file": "/Users/dev/project/src/UserService.java",
  "line": 42
}
```

**Suspend Policy Values:**
- `all` - Suspend all threads (default)
- `thread` - Suspend only the hitting thread
- `none` - Don't suspend (tracepoint)

---

### remove_breakpoint

Removes a breakpoint by its ID. Use `list_breakpoints` to get breakpoint IDs.

**Use when:**
- Removing a specific breakpoint
- Cleaning up after debugging

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `breakpoint_id` | string | Yes | ID of breakpoint to remove (from `list_breakpoints`) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "remove_breakpoint",
    "arguments": {
      "breakpoint_id": "bp_123"
    }
  }
}
```

**Example Response:**

```json
{
  "status": "removed",
  "breakpointId": "bp_123",
  "message": "Breakpoint removed successfully"
}
```

---

## Execution Control Tools

### resume_execution

Resumes program execution from a paused state.

**Use when:**
- Continuing after hitting a breakpoint
- Resuming after inspecting state
- Letting execution continue to the next breakpoint

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to resume |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "resume_execution",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "status": "resumed",
  "sessionId": "session_12345",
  "message": "Execution resumed"
}
```

---

### pause_execution

Pauses a running debug session at its current execution point.

**Use when:**
- Stopping execution to inspect state
- Pausing to examine what the program is doing
- Breaking into a running program

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to pause |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "pause_execution",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "status": "paused",
  "sessionId": "session_12345",
  "message": "Execution paused"
}
```

---

### step_over

Steps over to the next line without entering method calls.

**Use when:**
- Moving to the next line
- Skipping over method call details

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to step |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "step_over",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "status": "stepped",
  "action": "step_over",
  "sessionId": "session_12345",
  "message": "Stepped over"
}
```

---

### step_into

Steps into the method call on the current line.

**Use when:**
- Entering a method to debug it
- Drilling into implementation details

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to step |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "step_into",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "status": "stepped",
  "action": "step_into",
  "sessionId": "session_12345",
  "message": "Stepped into"
}
```

---

### step_out

Steps out of the current method, returning to the caller.

**Use when:**
- Finishing debugging current method
- Returning to calling context

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session to step |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "step_out",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "status": "stepped",
  "action": "step_out",
  "sessionId": "session_12345",
  "message": "Stepped out"
}
```

---

### run_to_line

Continues execution until the specified line is reached.

**Use when:**
- Skipping ahead to a specific line
- Running to a point of interest without setting a permanent breakpoint

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | Yes | Absolute path to target file |
| `line` | integer | Yes | 1-based line number to run to |
| `session_id` | string | No | Session to run |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "run_to_line",
    "arguments": {
      "file_path": "/Users/dev/project/src/UserService.java",
      "line": 58
    }
  }
}
```

**Example Response:**

```json
{
  "status": "running_to_line",
  "sessionId": "session_12345",
  "targetFile": "/Users/dev/project/src/UserService.java",
  "targetLine": 58,
  "message": "Running to UserService.java:58"
}
```

---

## Stack & Thread Tools

### get_stack_trace

Gets the current call stack.

**Use when:**
- Understanding how execution reached current point
- Analyzing call hierarchy

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `max_frames` | integer | No | Maximum frames to return (default: 50) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "get_stack_trace",
    "arguments": {
      "max_frames": 20
    }
  }
}
```

**Example Response:**

```json
{
  "sessionId": "session_12345",
  "frames": [
    {
      "index": 0,
      "file": "/Users/dev/project/src/UserService.java",
      "line": 42,
      "className": "com.example.UserService",
      "methodName": "findUser",
      "isCurrent": true,
      "isLibrary": false
    },
    {
      "index": 1,
      "file": "/Users/dev/project/src/UserController.java",
      "line": 28,
      "className": "com.example.UserController",
      "methodName": "getUser",
      "isCurrent": false,
      "isLibrary": false
    },
    {
      "index": 2,
      "file": null,
      "line": null,
      "className": "sun.reflect.NativeMethodAccessorImpl",
      "methodName": "invoke0",
      "isCurrent": false,
      "isLibrary": true
    }
  ],
  "totalFrames": 15
}
```

---

### select_stack_frame

Selects a stack frame to change the debugger context.

**Use when:**
- Examining variables in a different call context
- Navigating up the call stack

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `frame_index` | integer | Yes | 0-based frame index (0 = top/current) |
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "select_stack_frame",
    "arguments": {
      "frame_index": 1
    }
  }
}
```

**Example Response:**

```json
{
  "status": "selected",
  "frameIndex": 1,
  "location": {
    "file": "/Users/dev/project/src/UserController.java",
    "line": 28,
    "className": "com.example.UserController",
    "methodName": "getUser"
  },
  "message": "Selected frame 1: UserController.getUser()"
}
```

---

### list_threads

Lists all threads in the debug session.

**Use when:**
- Viewing thread states
- Finding blocked or waiting threads
- Multi-threaded debugging

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "list_threads",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "threads": [
    {
      "id": "thread_1",
      "name": "main",
      "state": "paused",
      "isCurrent": true
    },
    {
      "id": "thread_2",
      "name": "pool-1-thread-1",
      "state": "running",
      "isCurrent": false
    },
    {
      "id": "thread_3",
      "name": "Finalizer",
      "state": "waiting",
      "isCurrent": false
    }
  ],
  "count": 3
}
```

**Thread State Values:**
- `running` - Executing
- `paused` - Suspended at breakpoint
- `waiting` - Waiting on monitor
- `blocked` - Blocked on lock

---

## Variable Tools

### get_variables

Gets all variables visible in the current stack frame.

**Use when:**
- Inspecting local variables
- Viewing method arguments
- Checking object state

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `session_id` | string | No | Session ID |
| `frame_index` | integer | No | Stack frame index (default: 0) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "get_variables",
    "arguments": {
      "frame_index": 0
    }
  }
}
```

**Example Response:**

```json
{
  "sessionId": "session_12345",
  "frameIndex": 0,
  "variables": [
    {
      "name": "userId",
      "value": "\"user-123\"",
      "type": "String",
      "hasChildren": false
    },
    {
      "name": "user",
      "value": "null",
      "type": "User",
      "hasChildren": false
    },
    {
      "name": "this",
      "value": "UserService@a1b2c3",
      "type": "com.example.UserService",
      "hasChildren": true
    }
  ]
}
```

---

### set_variable

Modifies a variable's value during debugging.

**Use when:**
- Testing different values
- Fixing values to continue execution
- Exploring code paths

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `variable_path` | string | Yes | Path to variable |
| `value` | string | Yes | New value (as expression) |
| `session_id` | string | No | Session ID |
| `frame_index` | integer | No | Stack frame index (default: 0) |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "set_variable",
    "arguments": {
      "variable_path": "userId",
      "value": "\"admin\""
    }
  }
}
```

**Example Response:**

```json
{
  "status": "set",
  "variable": "userId",
  "oldValue": "\"user-123\"",
  "newValue": "\"admin\"",
  "message": "Variable 'userId' set to \"admin\""
}
```

---

## Navigation Tools

### get_source_context

Gets source code around the current execution point or specified location.

**Use when:**
- Viewing code context without switching to IDE
- Understanding surrounding code

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | No | File path (uses current location if omitted) |
| `line` | integer | No | Center line (uses current if omitted) |
| `context_lines` | integer | No | Lines above/below (default: 10) |
| `session_id` | string | No | Session ID |
| `project_path` | string | No | Project path |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "get_source_context",
    "arguments": {
      "context_lines": 5
    }
  }
}
```

**Example Response:**

```json
{
  "file": "/Users/dev/project/src/UserService.java",
  "startLine": 37,
  "endLine": 47,
  "currentLine": 42,
  "lines": [
    { "number": 37, "content": "    public User findUser(String userId) {", "isCurrent": false },
    { "number": 38, "content": "        logger.info(\"Finding user: {}\", userId);", "isCurrent": false },
    { "number": 39, "content": "", "isCurrent": false },
    { "number": 40, "content": "        User user = userRepository.findById(userId);", "isCurrent": false },
    { "number": 41, "content": "", "isCurrent": false },
    { "number": 42, "content": "        if (user == null) {", "isCurrent": true },
    { "number": 43, "content": "            throw new UserNotFoundException(userId);", "isCurrent": false },
    { "number": 44, "content": "        }", "isCurrent": false },
    { "number": 45, "content": "", "isCurrent": false },
    { "number": 46, "content": "        return user;", "isCurrent": false },
    { "number": 47, "content": "    }", "isCurrent": false }
  ],
  "breakpointsInView": [42]
}
```

---

## Evaluation Tools

### evaluate_expression

Evaluates an arbitrary expression in the current debug context and returns the result.

**Use when:**
- Inspecting computed values
- Calling methods to test behavior
- Checking conditions
- Running code snippets
- Computing complex expressions

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `expression` | string | Yes | Expression to evaluate (e.g., `x`, `list.size()`, `a + b * 2`) |
| `session_id` | string | No | Session ID |
| `frame_index` | integer | No | Stack frame context (default: 0) |
| `project_path` | string | No | Project path |

**Example Request (simple):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "evaluate_expression",
    "arguments": {
      "expression": "userId.length()"
    }
  }
}
```

**Example Request (method call):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "evaluate_expression",
    "arguments": {
      "expression": "userRepository.count()"
    }
  }
}
```

**Example Response:**

```json
{
  "sessionId": "session_12345",
  "frameIndex": 0,
  "result": {
    "expression": "userId.length()",
    "value": "8",
    "type": "int",
    "hasChildren": false
  }
}
```

**Example Error Response:**

```json
{
  "sessionId": "session_12345",
  "frameIndex": 0,
  "result": {
    "expression": "nonExistentVar",
    "value": "",
    "type": "error",
    "hasChildren": false,
    "error": "Cannot resolve symbol 'nonExistentVar'"
  }
}
```

---

## Error Handling

### JSON-RPC Standard Errors

| Code | Name | When It Occurs |
|------|------|----------------|
| -32700 | Parse Error | Invalid JSON in request |
| -32600 | Invalid Request | Missing required JSON-RPC fields |
| -32601 | Method Not Found | Unknown tool or method name |
| -32602 | Invalid Params | Missing or invalid parameters |
| -32603 | Internal Error | Unexpected server error |

### Custom MCP Errors

| Code | Name | When It Occurs |
|------|------|----------------|
| -32001 | Session Not Found | Debug session doesn't exist |
| -32002 | File Not Found | Specified file doesn't exist |
| -32003 | Not Paused | Operation requires paused session |
| -32004 | Breakpoint Error | Failed to set/remove breakpoint |
| -32005 | Evaluation Error | Expression evaluation failed |

### Example Error Response

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32003,
    "message": "Session must be paused to get variables"
  }
}
```

### Handling Session State

Before calling tools that require a paused session (get_variables, evaluate, step_*, etc.), you can check the session state:

```json
{
  "method": "tools/call",
  "params": {
    "name": "get_debug_session_status",
    "arguments": {}
  }
}
```

If `state` is not `"paused"`, wait for a breakpoint to be hit or call `pause`.

### Typical Debugging Flow

1. **Set breakpoints** - Use `set_breakpoint` at points of interest
2. **Start debugging** - Use `start_debug_session` or `execute_run_configuration`
3. **Wait for pause** - Session pauses at breakpoint
4. **Inspect state** - Use `get_debug_session_status` for comprehensive view
5. **Evaluate/modify** - Use `evaluate_expression`, `get_variables`, `set_variable`
6. **Navigate** - Use `step_over`, `step_into`, `step_out`, or `resume_execution`
7. **Repeat** - Continue inspecting and stepping until issue found
8. **Clean up** - Use `stop_debug_session` and `remove_breakpoint`
