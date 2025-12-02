# IntelliJ Debugger MCP Plugin - Requirements Document

## 1. Executive Summary

### 1.1 Project Overview
This project aims to create an IntelliJ Platform plugin that exposes debugger capabilities through a Model Context Protocol (MCP) server. This enables AI coding agents (such as Claude, GPT-based assistants, and other LLM-powered tools) to programmatically interact with the IntelliJ debugger—setting breakpoints, controlling execution, inspecting variables, evaluating expressions, and more.

### 1.2 Problem Statement
AI coding agents currently lack the ability to autonomously debug code within JetBrains IDEs. While they can read and write code, they cannot:
- Set and manage breakpoints
- Start, pause, or step through debug sessions
- Inspect runtime state (variables, stack frames)
- Evaluate expressions in the current context
- Navigate the call stack

### 1.3 Solution
Extend the existing JetBrains MCP Server infrastructure (built into IntelliJ 2025.2+) with debugger-specific tools that expose the XDebugger API through MCP, enabling AI agents to perform debugging tasks autonomously.

### 1.4 Target Users
- AI coding agents (Claude Code, Cursor, Continue, etc.)
- Developers using AI-assisted debugging workflows
- Automation systems requiring programmatic debugger access

---

## 2. Functional Requirements

### 2.0 Common Parameters: Project Resolution

#### Multi-Project Handling

All tools accept an optional `projectPath` parameter to specify which project to operate on when multiple projects are open in the IDE.

**Parameter:**
```json
{
  "projectPath": "/absolute/path/to/project"
}
```

**Behavior:**
| Scenario | Behavior |
|----------|----------|
| Single project open, `projectPath` omitted | Use the single open project |
| Single project open, `projectPath` provided | Validate and use if matches, error if not |
| Multiple projects open, `projectPath` omitted | Return error with list of open projects |
| Multiple projects open, `projectPath` provided | Use specified project, error if not found |

**Error Response (Multiple Projects, No projectPath):**
```json
{
  "error": "multiple_projects_open",
  "message": "Multiple projects are open. Please specify 'projectPath' parameter.",
  "open_projects": [
    {
      "name": "my-backend",
      "path": "/Users/dev/projects/my-backend"
    },
    {
      "name": "my-frontend",
      "path": "/Users/dev/projects/my-frontend"
    }
  ]
}
```

**Implementation Notes:**
- Use `ProjectManager.getInstance().openProjects` to get all open projects
- Match `projectPath` against `project.basePath`
- This pattern follows the existing JetBrains MCP Server conventions

---

### 2.1 Run Configuration Management

#### FR-2.1.1: List Run Configurations
**Description:** List all available run configurations in the project.

**Input:**
```json
{
  "name": "list_run_configurations",
  "arguments": {
    "projectPath": "/path/to/project"
  }
}
```

**Output:**
```json
{
  "run_configurations": [
    {
      "name": "MyApplication",
      "type": "Application",
      "type_id": "Application",
      "is_temporary": false,
      "can_run": true,
      "can_debug": true,
      "folder": "backend",
      "description": "Main application entry point"
    },
    {
      "name": "All Tests",
      "type": "JUnit",
      "type_id": "JUnit",
      "is_temporary": false,
      "can_run": true,
      "can_debug": true,
      "folder": null,
      "description": null
    },
    {
      "name": "npm start",
      "type": "npm",
      "type_id": "js.build_tools.npm",
      "is_temporary": false,
      "can_run": true,
      "can_debug": false,
      "folder": "scripts",
      "description": "Start development server"
    }
  ]
}
```

**Acceptance Criteria:**
- Returns all RunConfiguration instances from RunManager
- Includes configuration type for filtering
- Indicates if configuration supports debugging (has ProgramRunner for Debug executor)
- Includes folder organization if present
- Sorted alphabetically by name

#### FR-2.1.2: Run Configuration (Without Debugging)
**Description:** Execute a run configuration without debugging.

**Input:**
```json
{
  "name": "run_configuration",
  "arguments": {
    "projectPath": "/path/to/project",
    "configuration_name": "MyApplication",
    "wait_for_completion": false
  }
}
```

**Output:**
```json
{
  "status": "started|completed|failed",
  "process_id": 12345,
  "exit_code": null,
  "message": "Run configuration 'MyApplication' started"
}
```

**Acceptance Criteria:**
- Executes using DefaultRunExecutor (not Debug)
- Returns error if configuration not found
- Optional `wait_for_completion` to block until process exits
- Returns exit code if `wait_for_completion` is true
- Requires user confirmation unless "brave mode" enabled

---

### 2.2 Debug Session Management

#### FR-2.2.1: List Debug Sessions
**Description:** List all active debug sessions in the project (or all projects if multiple are open).

**Input:**
```json
{
  "name": "list_debug_sessions",
  "arguments": {
    "projectPath": "/path/to/project"
  }
}
```

**Output:**
```json
{
  "sessions": [
    {
      "id": "session_uuid",
      "name": "MyApplication",
      "state": "paused|running|stopped",
      "is_current": true,
      "run_configuration": "MyApplication",
      "process_id": 12345
    }
  ]
}
```

**Acceptance Criteria:**
- Returns all active XDebugSession instances
- Includes session state (running, paused, stopped)
- Indicates which session is currently focused
- Returns empty array if no sessions exist

#### FR-2.2.2: Start Debug Session
**Description:** Start a debug session for a specified run configuration.

**Input:**
```json
{
  "name": "start_debug_session",
  "arguments": {
    "projectPath": "/path/to/project",
    "configuration_name": "MyApplication",
    "wait_for_pause": true
  }
}
```

**Output:**
```json
{
  "session_id": "session_uuid",
  "status": "started|waiting_for_connection",
  "message": "Debug session started for MyApplication"
}
```

**Acceptance Criteria:**
- Starts debug session using DefaultDebugExecutor
- Configuration name must match one from `list_run_configurations`
- Configuration must support debugging (`can_debug: true`)
- Optional parameter to wait until first breakpoint hit
- Returns error if run configuration not found or doesn't support debugging
- Requires user confirmation unless "brave mode" enabled

**Note:** Use `list_run_configurations` first to discover available configurations and verify they support debugging.

#### FR-2.2.3: Stop Debug Session
**Description:** Stop an active debug session.

**Input:**
```json
{
  "name": "stop_debug_session",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid"
  }
}
```

**Acceptance Criteria:**
- Terminates the specified debug session
- If session_id omitted, stops current session in the project
- Returns error if session not found

#### FR-2.2.4: Get Debug Session Status
**Description:** Get comprehensive status of a debug session including variables, watches, stack summary, and source context. This is the primary tool for understanding the current debug state in a single call.

**Input:**
```json
{
  "name": "get_debug_session_status",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "include_variables": true,
    "include_source_context": true,
    "source_context_lines": 5,
    "max_stack_frames": 5
  }
}
```

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectPath` | No | Project path (required if multiple projects open) |
| `session_id` | No | Debug session ID (uses current session if omitted) |
| `include_variables` | No | Include variables from current frame. Default: true |
| `include_source_context` | No | Include source code around current line. Default: true |
| `source_context_lines` | No | Lines of context above/below current line. Default: 5 |
| `max_stack_frames` | No | Maximum stack frames in summary. Default: 5 |

**Output:**
```json
{
  "session_id": "session_uuid",
  "name": "MyApplication",
  "state": "paused",
  "paused_reason": "breakpoint",

  "current_location": {
    "file": "/path/to/File.java",
    "line": 42,
    "class": "com.example.MyClass",
    "method": "myMethod",
    "signature": "myMethod(int, String)"
  },

  "breakpoint_hit": {
    "id": "bp_uuid",
    "condition": "x > 10",
    "condition_result": true,
    "hit_count": 3
  },

  "stack_summary": [
    {
      "index": 0,
      "file": "/path/to/File.java",
      "line": 42,
      "class": "com.example.MyClass",
      "method": "myMethod",
      "is_current": true
    },
    {
      "index": 1,
      "file": "/path/to/Caller.java",
      "line": 15,
      "class": "com.example.Caller",
      "method": "invoke"
    }
  ],
  "total_stack_depth": 8,

  "variables": [
    {
      "name": "x",
      "value": "42",
      "type": "int",
      "has_children": false
    },
    {
      "name": "items",
      "value": "ArrayList@12345",
      "type": "java.util.ArrayList",
      "has_children": true,
      "id": "var_uuid"
    }
  ],

  "watches": [
    {
      "id": "watch_uuid",
      "expression": "items.size()",
      "value": "3",
      "type": "int",
      "error": null
    },
    {
      "id": "watch_uuid2",
      "expression": "user.getName()",
      "value": null,
      "type": null,
      "error": "Cannot evaluate: user is null"
    }
  ],

  "source_context": {
    "file": "/path/to/File.java",
    "start_line": 37,
    "end_line": 47,
    "current_line": 42,
    "lines": [
      { "number": 37, "content": "    public int myMethod(int x, String s) {" },
      { "number": 38, "content": "        int result = 0;" },
      { "number": 39, "content": "        for (Item item : items) {" },
      { "number": 40, "content": "            result += item.getValue();" },
      { "number": 41, "content": "        }" },
      { "number": 42, "content": ">>>     return result * x;", "is_current": true },
      { "number": 43, "content": "    }" },
      { "number": 44, "content": "" },
      { "number": 45, "content": "    private void helper() {" },
      { "number": 46, "content": "        // ..." },
      { "number": 47, "content": "    }" }
    ],
    "breakpoints_in_view": [42]
  },

  "current_thread": {
    "id": "thread_1",
    "name": "main",
    "state": "paused"
  },
  "thread_count": 5
}
```

**Acceptance Criteria:**
- Returns comprehensive debug state in a single call
- Includes current location with class/method information
- Shows breakpoint that was hit (if applicable) with condition details
- Provides stack summary (top N frames) with total depth
- Includes all variables visible in current frame (when `include_variables: true`)
- Includes all watch expressions with current values or errors
- Provides source code context around current line (when `include_source_context: true`)
- Shows current thread information and total thread count
- `paused_reason` indicates why execution stopped: `breakpoint`, `step`, `pause`, `exception`
- Handles running state gracefully (limited info when not paused)

**Note:** This rich response reduces round-trips for AI agents. Instead of calling multiple tools (`get_variables`, `get_stack_trace`, `get_source_context`), agents can get all essential debug context in one call.

---

### 2.3 Breakpoint Management

#### FR-2.3.1: List Breakpoints
**Description:** List breakpoints in the project. Can list all breakpoints or filter by file.

**Input:**
```json
{
  "name": "list_breakpoints",
  "arguments": {
    "projectPath": "/path/to/project",
    "file_path": "/path/to/File.java",
    "type": "line|exception|method|field|all",
    "enabled_only": false
  }
}
```

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectPath` | No | Project path (required if multiple projects open) |
| `file_path` | No | Filter breakpoints to specific file. If omitted, returns ALL breakpoints in project |
| `type` | No | Filter by breakpoint type. Default: "all" |
| `enabled_only` | No | If true, only return enabled breakpoints. Default: false |

**Output:**
```json
{
  "breakpoints": [
    {
      "id": "bp_uuid",
      "type": "line",
      "file": "/path/to/File.java",
      "line": 42,
      "enabled": true,
      "condition": "x > 10",
      "log_message": null,
      "suspend_policy": "all",
      "hit_count": 0,
      "temporary": false
    },
    {
      "id": "bp_uuid2",
      "type": "exception",
      "exception_class": "java.lang.NullPointerException",
      "file": null,
      "line": null,
      "enabled": true,
      "caught": true,
      "uncaught": true,
      "condition": null
    }
  ],
  "total_count": 2
}
```

**Acceptance Criteria:**
- Uses `XBreakpointManager.getAllBreakpoints()` when no filters specified
- Optional filtering by file path using `findBreakpointsAtLine` or iteration
- Optional filtering by breakpoint type
- Optional filtering by enabled state
- Includes breakpoint metadata (condition, log message, exception settings)
- Returns empty array if no breakpoints exist

#### FR-2.3.2: Set Line Breakpoint
**Description:** Add a line breakpoint at a specified location.

**Input:**
```json
{
  "name": "set_breakpoint",
  "arguments": {
    "projectPath": "/path/to/project",
    "file_path": "/path/to/File.java",
    "line": 42,
    "condition": "x > 10",
    "log_message": "Variable x = {x}",
    "suspend_policy": "all",
    "enabled": true,
    "temporary": false
  }
}
```

**Output:**
```json
{
  "breakpoint_id": "bp_uuid",
  "status": "set|invalid_location|already_exists",
  "verified": true,
  "message": "Breakpoint set at File.java:42"
}
```

**Acceptance Criteria:**
- Creates XLineBreakpoint at specified location
- Supports conditional breakpoints
- Supports log message breakpoints (tracepoints)
- Returns appropriate error for invalid locations
- Verifies breakpoint can be hit (valid code location)

#### FR-2.3.3: Remove Breakpoint
**Description:** Remove a breakpoint by ID or location.

**Input (by ID):**
```json
{
  "name": "remove_breakpoint",
  "arguments": {
    "projectPath": "/path/to/project",
    "breakpoint_id": "bp_uuid"
  }
}
```

**Input (by location):**
```json
{
  "name": "remove_breakpoint",
  "arguments": {
    "projectPath": "/path/to/project",
    "file_path": "/path/to/File.java",
    "line": 42
  }
}
```

**Acceptance Criteria:**
- Removes breakpoint by ID or file+line
- Returns success even if breakpoint doesn't exist (idempotent)
- Supports removing all breakpoints in a file when only `file_path` provided

#### FR-2.3.4: Set Exception Breakpoint
**Description:** Set a breakpoint that triggers on exceptions.

**Input:**
```json
{
  "name": "set_exception_breakpoint",
  "arguments": {
    "projectPath": "/path/to/project",
    "exception_class": "java.lang.NullPointerException",
    "caught": true,
    "uncaught": true,
    "condition": null
  }
}
```

---

### 2.4 Execution Control

#### FR-2.4.1: Resume Execution
**Description:** Resume execution of a paused debug session.

**Input:**
```json
{
  "name": "resume",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid"
  }
}
```

**Acceptance Criteria:**
- Resumes execution of paused session
- If session_id omitted, resumes current session in the project
- Returns error if session not paused

#### FR-2.4.2: Pause Execution
**Description:** Pause a running debug session.

**Input:**
```json
{
  "name": "pause",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid"
  }
}
```

#### FR-2.4.3: Step Over
**Description:** Execute next line, stepping over method calls.

**Input:**
```json
{
  "name": "step_over",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "ignore_breakpoints": false
  }
}
```

**Output:**
```json
{
  "status": "stepped",
  "new_location": {
    "file": "/path/to/File.java",
    "line": 43,
    "method": "myMethod"
  }
}
```

#### FR-2.4.4: Step Into
**Description:** Execute next line, stepping into method calls.

**Input:**
```json
{
  "name": "step_into",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "force": false
  }
}
```

**Acceptance Criteria:**
- Steps into next method call
- `force` parameter enables stepping into library code

#### FR-2.4.5: Step Out
**Description:** Continue execution until current method returns.

**Input:**
```json
{
  "name": "step_out",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid"
  }
}
```

#### FR-2.4.6: Run to Cursor/Line
**Description:** Continue execution until a specific line is reached.

**Input:**
```json
{
  "name": "run_to_line",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "file_path": "/path/to/File.java",
    "line": 50,
    "ignore_breakpoints": false
  }
}
```

---

### 2.5 Stack Frame Navigation

#### FR-2.5.1: Get Stack Trace
**Description:** Get the current call stack.

**Input:**
```json
{
  "name": "get_stack_trace",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "thread_name": "main",
    "max_frames": 50
  }
}
```

**Output:**
```json
{
  "thread": "main",
  "frames": [
    {
      "index": 0,
      "file": "/path/to/File.java",
      "line": 42,
      "class": "com.example.MyClass",
      "method": "myMethod",
      "is_current": true,
      "is_library": false
    },
    {
      "index": 1,
      "file": "/path/to/Caller.java",
      "line": 15,
      "class": "com.example.Caller",
      "method": "callMyMethod",
      "is_current": false,
      "is_library": false
    }
  ]
}
```

**Acceptance Criteria:**
- Returns all stack frames for current/specified thread
- Includes source file and line information
- Identifies library frames
- Supports limiting number of frames

#### FR-2.5.2: Select Stack Frame
**Description:** Change the current stack frame context.

**Input:**
```json
{
  "name": "select_stack_frame",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "frame_index": 2
  }
}
```

**Acceptance Criteria:**
- Changes debugger focus to specified frame
- Updates variables view context
- Enables evaluation in frame context

#### FR-2.5.3: List Threads
**Description:** List all threads in the debugged process.

**Input:**
```json
{
  "name": "list_threads",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid"
  }
}
```

**Output:**
```json
{
  "threads": [
    {
      "id": "thread_id",
      "name": "main",
      "state": "paused|running|waiting",
      "is_current": true,
      "group": "main"
    }
  ]
}
```

---

### 2.6 Variable Inspection

#### FR-2.6.1: Get Variables
**Description:** Get variables visible in the current stack frame.

**Input:**
```json
{
  "name": "get_variables",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "frame_index": 0,
    "scope": "local|arguments|this|all"
  }
}
```

**Output:**
```json
{
  "variables": [
    {
      "name": "myVariable",
      "value": "42",
      "type": "int",
      "has_children": false,
      "id": "var_uuid"
    },
    {
      "name": "myObject",
      "value": "MyClass@12345",
      "type": "com.example.MyClass",
      "has_children": true,
      "id": "var_uuid2"
    }
  ]
}
```

**Acceptance Criteria:**
- Returns all visible variables in frame
- Supports filtering by scope
- Indicates if variable has expandable children
- Handles large objects gracefully (truncation)

#### FR-2.6.2: Expand Variable
**Description:** Get child properties of an object variable.

**Input:**
```json
{
  "name": "expand_variable",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "variable_id": "var_uuid",
    "path": "myObject.innerField"
  }
}
```

**Output:**
```json
{
  "children": [
    {
      "name": "field1",
      "value": "value1",
      "type": "String",
      "has_children": false
    }
  ]
}
```

#### FR-2.6.3: Set Variable Value
**Description:** Modify the value of a variable during debugging.

**Input:**
```json
{
  "name": "set_variable",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "variable_id": "var_uuid",
    "new_value": "100"
  }
}
```

**Acceptance Criteria:**
- Modifies variable value in running process
- Validates type compatibility
- Returns error if variable is read-only
- Requires user confirmation unless "brave mode" enabled

---

### 2.7 Expression Evaluation

#### FR-2.7.1: Evaluate
**Description:** Evaluate an expression or code fragment in the current debug context.

**Input:**
```json
{
  "name": "evaluate",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "expression": "myVariable + 10",
    "frame_index": 0,
    "allow_side_effects": false
  }
}
```

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectPath` | No | Project path (required if multiple projects open) |
| `session_id` | No | Debug session ID (uses current session if omitted) |
| `expression` | Yes | Expression or code fragment to evaluate. Can be single expression or multi-line code |
| `frame_index` | No | Stack frame context for evaluation. Default: 0 (current frame) |
| `allow_side_effects` | No | Allow method calls and state modifications. Default: false |

**Output:**
```json
{
  "result": {
    "value": "52",
    "type": "int",
    "has_children": false,
    "id": "result_uuid"
  },
  "error": null
}
```

**Acceptance Criteria:**
- Evaluates single expressions (e.g., `myVariable + 10`)
- Evaluates multi-line code fragments (e.g., `int sum = 0; for (...) { ... }; return sum;`)
- `allow_side_effects` controls whether method invocations and state changes are permitted
- Returns structured result with type information
- For objects, returns `has_children: true` and `id` for use with `expand_variable`
- Handles evaluation errors gracefully with descriptive error messages

**Examples:**
```json
// Simple expression
{ "expression": "items.size()" }

// Expression with side effects (method call that modifies state)
{ "expression": "list.add(newItem)", "allow_side_effects": true }

// Multi-line code fragment
{ "expression": "int sum = 0;\nfor (int x : values) { sum += x; }\nreturn sum;", "allow_side_effects": true }
```

---

### 2.8 Watch Management

**Note:** Watch values are automatically included in `get_debug_session_status` response. Use these tools to manage the watch list.

#### FR-2.8.1: Add Watch
**Description:** Add a watch expression.

**Input:**
```json
{
  "name": "add_watch",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "expression": "myObject.getValue()"
  }
}
```

#### FR-2.8.3: Remove Watch
**Description:** Remove a watch expression.

**Input:**
```json
{
  "name": "remove_watch",
  "arguments": {
    "projectPath": "/path/to/project",
    "session_id": "session_uuid",
    "watch_id": "watch_uuid"
  }
}
```

---

### 2.9 Source Navigation

#### FR-2.9.1: Get Source Context
**Description:** Get source code around the current execution point.

**Input:**
```json
{
  "name": "get_source_context",
  "arguments": {
    "projectPath": "/path/to/project",
    "file_path": "/path/to/File.java",
    "line": 42,
    "context_lines": 10
  }
}
```

**Output:**
```json
{
  "file": "/path/to/File.java",
  "start_line": 32,
  "end_line": 52,
  "current_line": 42,
  "source": "... source code with line numbers ...",
  "breakpoints": [42, 45]
}
```

---

### 2.10 Tool Window UI

#### FR-2.10.1: Tool Window Layout
**Description:** A dedicated tool window for monitoring MCP server status and command history.

**Layout Structure:**
```
┌─────────────────────────────────────────────────────────────┐
│ [Toolbar: Refresh | Copy URL | Clear | Export] [Install ▼] │
├─────────────────────────────────────────────────────────────┤
│ ServerStatusPanel                                           │
│ ● MCP Server Running | http://localhost:63342/... | Project │
├─────────────────────────────────────────────────────────────┤
│ AgentRuleTipPanel (Yellow background)                       │
│ ℹ Tip: Copy this rule to CLAUDE.md...        [Copy rule]   │
├─────────────────────────────────────────────────────────────┤
│ FilterToolbar                                               │
│ Tool: [▼] | Status: [▼] | Search: [________]               │
├─────────────────────────────────────────────────────────────┤
│ Command History List (60%)                                  │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ 14:32:15  set_breakpoint                   SUCCESS      ││
│ │ 14:32:10  get_debug_session_status         SUCCESS      ││
│ │ 14:32:05  evaluate                         ERROR        ││
│ └─────────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────────┤
│ Details Area (40%)                                          │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Tool: set_breakpoint                                    ││
│ │ Status: SUCCESS                                         ││
│ │ Duration: 45ms                                          ││
│ │                                                         ││
│ │ Parameters:                                             ││
│ │ {"file_path": "/src/Main.java", "line": 42}            ││
│ │                                                         ││
│ │ Result:                                                 ││
│ │ {"breakpoint_id": "bp_123", "verified": true}          ││
│ └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

**Acceptance Criteria:**
- Tool window anchored at bottom of IDE
- Splitter between history list (60%) and details (40%)
- Real-time updates when commands are executed
- Auto-scroll to newest commands (configurable)

#### FR-2.10.2: Server Status Panel
**Description:** Display MCP server status and connection information.

**Components:**
- Status indicator with colored icon (green=running, red=error)
- Server URL (clickable to copy)
- Current project name

**Status Colors:**
| Status | Color (Light/Dark) |
|--------|-------------------|
| Running | `#59A869` (green) |
| Error | `#E05555` (red) |
| Pending | `#D9A343` (orange) |

#### FR-2.10.3: Agent Rule Tip Panel
**Description:** Yellow highlighted banner prompting users to add plugin rule to their AI agent config.

**Components:**
- Info icon (ℹ)
- Tip text: "Tip: Copy this rule to your CLAUDE.md/AGENTS.md/.cursorrules file"
- "Copy rule" link button

**Rule Text to Copy:**
```
IMPORTANT: When debugging, prefer using jetbrains-debugger MCP tools to interact with the IDE debugger.
```

**Styling:**
- Background: `JBColor(0xFFFBE6, 0x3D3D00)` (light yellow / dark gold)
- Link color: Blue with underline on hover
- Font size: 11pt

**Notification on Copy:**
Shows balloon notification with hints about config file locations:
- Claude Code: `CLAUDE.md` (project root) or `~/.claude/CLAUDE.md` (global)
- Cursor: `.cursorrules` or `.cursor/rules/*.mdc`
- Other agents: Check documentation

#### FR-2.10.4: Filter Toolbar
**Description:** Filter and search command history.

**Components:**
- Tool dropdown: All tools + "All" option
- Status dropdown: SUCCESS, ERROR, PENDING, All
- Search text field: Real-time filtering

**Behavior:**
- Filters combine with AND logic
- Search matches tool name, parameters, and results
- Filters persist during session

#### FR-2.10.5: Command History List
**Description:** Scrollable list of executed MCP commands.

**List Item Layout:**
```
[Timestamp]  [Tool Name]                    [Status]
14:32:15     set_breakpoint                 SUCCESS
```

**Styling:**
- Timestamp: Gray, `HH:mm:ss` format
- Tool name: Bold
- Status: Colored (green/red/orange)

**Behavior:**
- Newest commands at top
- Click to show details
- Auto-scroll configurable

#### FR-2.10.6: Command Details Area
**Description:** Display full details of selected command.

**Content:**
```
Tool: {tool_name}
Status: {status}
Timestamp: {ISO timestamp}
Duration: {ms}ms

Parameters:
{JSON formatted parameters}

Error: (if present)
{error message}

Result: (if present)
{JSON formatted result}

Affected Files: (if present)
  - file1.java
  - file2.java
```

**Styling:**
- Monospaced font (12pt)
- JSON pretty-printed
- Scrollable

#### FR-2.10.7: Install on Coding Agents Button
**Description:** Prominent button to help users configure AI coding agents.

**Popup Structure:**
```
┌─────────────────────────────────────────┐
│ Install on Coding Agents                │
├─────────────────────────────────────────┤
│ INSTALL NOW                             │
├─────────────────────────────────────────┤
│ ⚙ Claude Code (CLI)                    │
│   Run installation command              │
├─────────────────────────────────────────┤
│ ─────────────────────────────────────── │
├─────────────────────────────────────────┤
│ COPY CONFIGURATION                      │
├─────────────────────────────────────────┤
│ Claude Code (CLI)                       │
│ Claude Desktop                          │
│ Cursor                                  │
│ VS Code (Generic MCP)                   │
│ Windsurf                                │
└─────────────────────────────────────────┘
```

**Install Now Section:**
- Runs shell command directly: `claude mcp add --transport http jetbrains-debugger {url}`
- Shows success/failure notification
- Handles reinstall (removes first if exists)

**Copy Configuration Section:**
- Copies JSON config to clipboard
- Shows notification with config location hints

**Supported Clients:**
| Client | Config Format | Config Location |
|--------|--------------|-----------------|
| Claude Code | CLI command | Terminal |
| Claude Desktop | JSON | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Cursor | JSON | `.cursor/mcp.json` |
| VS Code | JSON | `.vscode/settings.json` |
| Windsurf | JSON | `~/.codeium/windsurf/mcp_config.json` |

#### FR-2.10.8: Toolbar Actions
**Description:** Quick action buttons in toolbar.

| Action | Icon | Description |
|--------|------|-------------|
| Refresh | `AllIcons.Actions.Refresh` | Refresh server status and history |
| Copy URL | `AllIcons.Actions.Copy` | Copy server URL to clipboard |
| Clear History | `AllIcons.Actions.GC` | Clear command history (with confirmation) |
| Export History | `AllIcons.ToolbarDecorator.Export` | Export to JSON/CSV file |

#### FR-2.10.9: Settings
**Description:** Plugin configuration in IDE Settings.

**Settings:**
| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| Max History Size | Integer | 100 | Maximum commands to keep in history |
| Auto-scroll | Boolean | true | Scroll to new commands automatically |

---

## 3. Non-Functional Requirements

### 3.1 Performance

| Metric | Requirement |
|--------|-------------|
| Tool response time (simple) | < 100ms |
| Tool response time (complex) | < 500ms |
| Variable expansion | < 200ms |
| Expression evaluation | < 1000ms |
| Concurrent sessions | Support 5+ |

### 3.2 Reliability

- **Availability:** Plugin should not crash the IDE
- **Error Recovery:** Graceful handling of debug session failures
- **State Consistency:** Accurate reflection of debugger state
- **Timeout Handling:** All operations should have configurable timeouts

### 3.3 Security

- **User Confirmation:** Destructive operations require confirmation by default
- **Brave Mode:** Opt-in mode to skip confirmations
- **No Credential Exposure:** Never expose sensitive data in responses
- **Input Validation:** Validate all tool inputs before processing
- **Path Validation:** Ensure file paths are within project scope

### 3.4 Compatibility

| Platform | Requirement |
|----------|-------------|
| IntelliJ IDEA | 2025.2+ (built-in MCP support) |
| Other JetBrains IDEs | PyCharm, WebStorm, etc. with XDebugger |
| MCP Protocol | Version 2025-03-26+ |
| JDK | 21+ (plugin runtime) |
| Kotlin | 2.x |

### 3.5 Scalability

- Support multiple concurrent debug sessions
- Handle large stack traces (100+ frames)
- Handle large variable trees (1000+ nodes)
- Support projects with many breakpoints (100+)

---

## 4. Technical Architecture

### 4.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      AI Coding Agent                        │
│                  (Claude, Cursor, etc.)                     │
└─────────────────────┬───────────────────────────────────────┘
                      │ MCP Protocol (STDIO/SSE)
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                  JetBrains MCP Proxy                        │
│              (mcp-jetbrains / built-in)                     │
└─────────────────────┬───────────────────────────────────────┘
                      │ Internal API
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              IntelliJ MCP Server (Built-in)                 │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         Debugger MCP Plugin (This Project)          │   │
│  │                                                     │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌────────────┐  │   │
│  │  │ Breakpoint  │  │  Execution  │  │  Variable  │  │   │
│  │  │   Tools     │  │   Tools     │  │   Tools    │  │   │
│  │  └──────┬──────┘  └──────┬──────┘  └─────┬──────┘  │   │
│  │         │                │               │         │   │
│  │         └────────────────┼───────────────┘         │   │
│  │                          │                         │   │
│  │                          ▼                         │   │
│  │  ┌─────────────────────────────────────────────┐   │   │
│  │  │           Debugger Service Layer            │   │   │
│  │  │    (Wraps XDebugger API, thread-safe)       │   │   │
│  │  └─────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────┬───────────────────────────────────────┘
                      │ IntelliJ Platform API
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                  IntelliJ XDebugger API                     │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │XDebugManager │  │XDebugSession │  │XBreakpointManager│  │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Component Design

#### 4.2.1 Debugger MCP Service
```kotlin
@Service(Service.Level.PROJECT)
class DebuggerMcpService(
    private val project: Project,
    private val scope: CoroutineScope
) {
    // Entry point for all debugger operations
    // Handles threading, state management, event subscriptions
}
```

#### 4.2.2 Tool Registration
Tools will be registered using the JetBrains MCP Server extension point:

```xml
<extensions defaultExtensionNs="com.intellij">
    <mcpServer.tool
        implementation="com.example.debugger.tools.SetBreakpointTool"/>
    <mcpServer.tool
        implementation="com.example.debugger.tools.EvaluateExpressionTool"/>
    <!-- Additional tools -->
</extensions>
```

#### 4.2.3 Threading Strategy
- **Tool handlers:** Execute on coroutine scope with `Dispatchers.Default`
- **Read operations:** Use `readAction {}` for PSI/VFS access
- **Write operations:** Use `writeAction {}` on EDT
- **Async debugger APIs:** Convert to suspending functions using `suspendCancellableCoroutine`

### 4.3 Data Flow

```
1. MCP Client sends tool request
2. MCP Server routes to tool handler
3. Tool handler validates input
4. DebuggerMcpService processes request
   a. Acquires appropriate locks (read/write action)
   b. Calls XDebugger API
   c. Handles async callbacks
5. Result serialized to MCP response
6. Response sent to MCP client
```

---

## 5. Implementation Phases

### Phase 1: Foundation (MVP)
**Duration:** 2 weeks

**Deliverables:**
- Plugin project setup with MCP Server extension
- Basic session management (list, status)
- Simple breakpoint operations (set, remove, list)
- Basic execution control (resume, pause, step over)

**Success Criteria:**
- AI agent can set a breakpoint and resume execution
- Plugin integrates with built-in MCP Server

### Phase 2: Core Debugging
**Duration:** 2 weeks

**Deliverables:**
- Full stepping support (step into, step out, run to line)
- Stack trace navigation
- Variable inspection (basic)
- Thread management

**Success Criteria:**
- AI agent can navigate a debugging session
- Variables visible at breakpoints

### Phase 3: Advanced Features
**Duration:** 2 weeks

**Deliverables:**
- Expression evaluation
- Watch management
- Conditional breakpoints
- Variable modification
- Exception breakpoints

**Success Criteria:**
- AI agent can evaluate expressions and modify state
- Full breakpoint configuration support

### Phase 4: Polish & Integration
**Duration:** 1 week

**Deliverables:**
- Documentation and examples
- Error handling improvements
- Performance optimization
- Integration testing with Claude Code

**Success Criteria:**
- Stable, production-ready plugin
- Published to JetBrains Marketplace

---

## 6. Testing Requirements

### 6.1 Unit Tests
- Test each tool handler in isolation
- Mock XDebugger API interactions
- Test input validation
- Test error handling

### 6.2 Integration Tests
- Test with real debug sessions
- Test multi-session scenarios
- Test concurrent tool invocations
- Test with various project types (Java, Kotlin, Python)

### 6.3 End-to-End Tests
- Test with actual MCP clients (Claude Code)
- Test debugging workflows
- Test edge cases (session crashes, disconnections)

### 6.4 Performance Tests
- Measure response times under load
- Test with large projects
- Test with many breakpoints
- Test with deep stack traces

---

## 7. Documentation Requirements

### 7.1 User Documentation
- Installation guide
- Configuration guide for MCP clients
- Tool reference with examples
- Troubleshooting guide

### 7.2 Developer Documentation
- Architecture overview
- Contributing guide
- API documentation
- Extension guide

---

## 8. Risks and Mitigations

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| XDebugger API changes | High | Medium | Abstract API layer, follow release notes |
| Threading issues | High | Medium | Comprehensive testing, use Platform patterns |
| Performance bottlenecks | Medium | Medium | Async design, caching, pagination |
| Security vulnerabilities | High | Low | Input validation, user confirmations |
| MCP spec changes | Medium | Low | Follow spec updates, version handling |

---

## 9. Success Metrics

| Metric | Target |
|--------|--------|
| Tool success rate | > 99% |
| Average response time | < 200ms |
| IDE crash rate | 0% |
| User satisfaction (if measured) | > 4/5 |
| Marketplace rating | > 4/5 |

---

## 10. Glossary

| Term | Definition |
|------|------------|
| **MCP** | Model Context Protocol - standard for AI-IDE integration |
| **XDebugger** | IntelliJ Platform's debugger framework |
| **EDT** | Event Dispatch Thread - IntelliJ's UI thread |
| **BGT** | Background Thread - non-UI threads |
| **Brave Mode** | Setting to skip user confirmations |
| **Stack Frame** | Single entry in call stack |
| **Watch** | Expression monitored across debug steps |

---

## 11. References

- [MCP Specification](https://modelcontextprotocol.io/specification)
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/about.html)
- [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin)
- [XDebugger API Source](https://github.com/JetBrains/intellij-community/tree/master/platform/xdebugger-api)

---

## Appendix A: Tool Summary

| Tool Name | Category | Priority |
|-----------|----------|----------|
| `list_run_configurations` | Run Configs | P0 |
| `run_configuration` | Run Configs | P0 |
| `list_debug_sessions` | Debug Session | P0 |
| `start_debug_session` | Debug Session | P0 |
| `stop_debug_session` | Debug Session | P0 |
| `get_debug_session_status` | Debug Session | P0 |
| `list_breakpoints` | Breakpoints | P0 |
| `set_breakpoint` | Breakpoints | P0 |
| `remove_breakpoint` | Breakpoints | P0 |
| `set_exception_breakpoint` | Breakpoints | P2 |
| `resume` | Execution | P0 |
| `pause` | Execution | P0 |
| `step_over` | Execution | P0 |
| `step_into` | Execution | P0 |
| `step_out` | Execution | P1 |
| `run_to_line` | Execution | P1 |
| `get_stack_trace` | Stack | P0 |
| `select_stack_frame` | Stack | P1 |
| `list_threads` | Stack | P1 |
| `get_variables` | Variables | P0 |
| `expand_variable` | Variables | P1 |
| `set_variable` | Variables | P2 |
| `evaluate` | Evaluation | P0 |
| `add_watch` | Watches | P2 |
| `remove_watch` | Watches | P2 |
| `get_source_context` | Navigation | P1 |

**Total Tools: 26**

**Priority Legend:**
- P0: Must have for MVP (16 tools)
- P1: Important for usability (6 tools)
- P2: Nice to have (4 tools)

**Consolidation Notes:**
- `toggle_breakpoint` removed (use `set_breakpoint` / `remove_breakpoint`)
- `evaluate_expression` and `evaluate_code_fragment` merged into `evaluate`
- `list_watches` removed (watches included in `get_debug_session_status`)

---

## Appendix B: Example AI Agent Workflow

### Example 1: Debugging a Calculation Bug

```
Agent: I need to debug why the calculateTotal function returns wrong values.

1. Agent: list_run_configurations(projectPath="/Users/dev/calculator")
   → Shows available configurations:
     - "CalculatorTest" (JUnit, can_debug=true)
     - "Calculator App" (Application, can_debug=true)
     - "npm build" (npm, can_debug=false)

2. Agent: list_breakpoints(projectPath="/Users/dev/calculator")
   → Shows: [] (no breakpoints set)

3. Agent: set_breakpoint(projectPath="/Users/dev/calculator", file_path="/src/Calculator.java", line=42)
   → Breakpoint set at Calculator.java:42

4. Agent: start_debug_session(projectPath="/Users/dev/calculator", configuration_name="CalculatorTest")
   → Debug session started, waiting for breakpoint

5. [Breakpoint hit]

6. Agent: get_debug_session_status(projectPath="/Users/dev/calculator")
   → Returns rich context in ONE call:
     - state: "paused"
     - paused_reason: "breakpoint"
     - current_location: { file: "Calculator.java", line: 42, method: "calculateTotal" }
     - variables: [items=[Item@1, Item@2], subtotal=0, tax=0]
     - watches: []
     - source_context: (shows code around line 42)
     - stack_summary: [calculateTotal <- runTest <- main]

7. Agent: evaluate(projectPath="/Users/dev/calculator", expression="items.stream().mapToDouble(Item::getPrice).sum()")
   → Result: 150.0

8. Agent: step_over(projectPath="/Users/dev/calculator")
   → Now at line 43

9. Agent: get_debug_session_status(projectPath="/Users/dev/calculator")
   → Returns updated state:
     - current_location: { line: 43 }
     - variables: [subtotal=150.0, tax=0]
     - source_context: (shows code around line 43)

10. Agent: step_over(projectPath="/Users/dev/calculator")
    → Now at line 44, tax calculation

11. Agent: evaluate(projectPath="/Users/dev/calculator", expression="subtotal * TAX_RATE")
    → Result: 0.0 (TAX_RATE is 0!)

12. Agent: "Found the bug! TAX_RATE constant is set to 0 instead of 0.08"
```

### Example 2: Multiple Projects Open

```
Agent: I want to set a breakpoint in the API server.

1. Agent: set_breakpoint(file_path="/src/ApiController.java", line=100)
   → Error: multiple_projects_open
     "Multiple projects are open. Please specify 'projectPath' parameter."
     open_projects: [
       { name: "api-server", path: "/Users/dev/api-server" },
       { name: "web-client", path: "/Users/dev/web-client" }
     ]

2. Agent: set_breakpoint(projectPath="/Users/dev/api-server", file_path="/src/ApiController.java", line=100)
   → Breakpoint set at ApiController.java:100
```

### Example 3: Running Without Debugging

```
Agent: Run the build script to compile the project.

1. Agent: list_run_configurations(projectPath="/Users/dev/myapp")
   → Shows:
     - "Build All" (Gradle, can_debug=false)
     - "Run Tests" (JUnit, can_debug=true)

2. Agent: run_configuration(projectPath="/Users/dev/myapp", configuration_name="Build All", wait_for_completion=true)
   → status: "completed", exit_code: 0, message: "Build successful"
```

### Example 4: Using Watches for Repeated Evaluation

```
Agent: I want to monitor how the 'total' variable changes through multiple steps.

1. Agent: add_watch(projectPath="/Users/dev/myapp", expression="order.getTotal()")
   → Watch added: watch_uuid

2. Agent: add_watch(projectPath="/Users/dev/myapp", expression="order.getItems().size()")
   → Watch added: watch_uuid2

3. Agent: step_over(projectPath="/Users/dev/myapp")
   → Stepped to line 55

4. Agent: get_debug_session_status(projectPath="/Users/dev/myapp")
   → Returns state including watches:
     - watches: [
         { expression: "order.getTotal()", value: "100.50" },
         { expression: "order.getItems().size()", value: "3" }
       ]

5. Agent: step_over(projectPath="/Users/dev/myapp")
   → Stepped to line 56

6. Agent: get_debug_session_status(projectPath="/Users/dev/myapp")
   → Returns updated state:
     - watches: [
         { expression: "order.getTotal()", value: "125.75" },  // Changed!
         { expression: "order.getItems().size()", value: "4" }  // Changed!
       ]

7. Agent: "The total increased from 100.50 to 125.75 after adding item #4"
```

These workflows demonstrate how an AI agent can efficiently debug code using fewer tool calls thanks to the rich `get_debug_session_status` response.
