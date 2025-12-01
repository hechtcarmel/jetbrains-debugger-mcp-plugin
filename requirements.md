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

### 2.1 Debug Session Management

#### FR-2.1.1: List Debug Sessions
**Description:** List all active debug sessions in the current project.

**Input:**
```json
{
  "name": "list_debug_sessions"
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

#### FR-2.1.2: Start Debug Session
**Description:** Start a debug session for a specified run configuration.

**Input:**
```json
{
  "name": "start_debug_session",
  "arguments": {
    "run_configuration": "MyApplication",
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
- Supports named run configurations
- Optional parameter to wait until first breakpoint hit
- Returns error if run configuration not found
- Requires user confirmation unless "brave mode" enabled

#### FR-2.1.3: Stop Debug Session
**Description:** Stop an active debug session.

**Input:**
```json
{
  "name": "stop_debug_session",
  "arguments": {
    "session_id": "session_uuid"
  }
}
```

**Acceptance Criteria:**
- Terminates the specified debug session
- If session_id omitted, stops current session
- Returns error if session not found

#### FR-2.1.4: Get Debug Session Status
**Description:** Get detailed status of a debug session.

**Input:**
```json
{
  "name": "get_debug_session_status",
  "arguments": {
    "session_id": "session_uuid"
  }
}
```

**Output:**
```json
{
  "session_id": "session_uuid",
  "state": "paused",
  "paused_at": {
    "file": "/path/to/File.java",
    "line": 42,
    "class": "com.example.MyClass",
    "method": "myMethod"
  },
  "breakpoints_hit": ["bp_id_1"],
  "thread_count": 5,
  "current_thread": "main"
}
```

---

### 2.2 Breakpoint Management

#### FR-2.2.1: List Breakpoints
**Description:** List all breakpoints in the current project.

**Input:**
```json
{
  "name": "list_breakpoints",
  "arguments": {
    "file_path": "/path/to/File.java",
    "enabled_only": false
  }
}
```

**Output:**
```json
{
  "breakpoints": [
    {
      "id": "bp_uuid",
      "type": "line|method|exception|field",
      "file": "/path/to/File.java",
      "line": 42,
      "enabled": true,
      "condition": "x > 10",
      "log_message": null,
      "suspend_policy": "all|thread|none",
      "hit_count": 0,
      "temporary": false
    }
  ]
}
```

**Acceptance Criteria:**
- Returns all XBreakpoint instances
- Optional filtering by file path
- Optional filtering by enabled state
- Includes breakpoint metadata (condition, log message)

#### FR-2.2.2: Set Line Breakpoint
**Description:** Add a line breakpoint at a specified location.

**Input:**
```json
{
  "name": "set_breakpoint",
  "arguments": {
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

#### FR-2.2.3: Remove Breakpoint
**Description:** Remove a breakpoint by ID or location.

**Input:**
```json
{
  "name": "remove_breakpoint",
  "arguments": {
    "breakpoint_id": "bp_uuid"
  }
}
```
OR
```json
{
  "name": "remove_breakpoint",
  "arguments": {
    "file_path": "/path/to/File.java",
    "line": 42
  }
}
```

**Acceptance Criteria:**
- Removes breakpoint by ID or file+line
- Returns success even if breakpoint doesn't exist (idempotent)
- Supports removing all breakpoints in a file

#### FR-2.2.4: Toggle Breakpoint
**Description:** Enable or disable a breakpoint without removing it.

**Input:**
```json
{
  "name": "toggle_breakpoint",
  "arguments": {
    "breakpoint_id": "bp_uuid",
    "enabled": false
  }
}
```

#### FR-2.2.5: Set Exception Breakpoint
**Description:** Set a breakpoint that triggers on exceptions.

**Input:**
```json
{
  "name": "set_exception_breakpoint",
  "arguments": {
    "exception_class": "java.lang.NullPointerException",
    "caught": true,
    "uncaught": true,
    "condition": null
  }
}
```

---

### 2.3 Execution Control

#### FR-2.3.1: Resume Execution
**Description:** Resume execution of a paused debug session.

**Input:**
```json
{
  "name": "resume",
  "arguments": {
    "session_id": "session_uuid"
  }
}
```

**Acceptance Criteria:**
- Resumes execution of paused session
- If session_id omitted, resumes current session
- Returns error if session not paused

#### FR-2.3.2: Pause Execution
**Description:** Pause a running debug session.

**Input:**
```json
{
  "name": "pause",
  "arguments": {
    "session_id": "session_uuid"
  }
}
```

#### FR-2.3.3: Step Over
**Description:** Execute next line, stepping over method calls.

**Input:**
```json
{
  "name": "step_over",
  "arguments": {
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

#### FR-2.3.4: Step Into
**Description:** Execute next line, stepping into method calls.

**Input:**
```json
{
  "name": "step_into",
  "arguments": {
    "session_id": "session_uuid",
    "force": false
  }
}
```

**Acceptance Criteria:**
- Steps into next method call
- `force` parameter enables stepping into library code

#### FR-2.3.5: Step Out
**Description:** Continue execution until current method returns.

**Input:**
```json
{
  "name": "step_out",
  "arguments": {
    "session_id": "session_uuid"
  }
}
```

#### FR-2.3.6: Run to Cursor/Line
**Description:** Continue execution until a specific line is reached.

**Input:**
```json
{
  "name": "run_to_line",
  "arguments": {
    "file_path": "/path/to/File.java",
    "line": 50,
    "ignore_breakpoints": false
  }
}
```

---

### 2.4 Stack Frame Navigation

#### FR-2.4.1: Get Stack Trace
**Description:** Get the current call stack.

**Input:**
```json
{
  "name": "get_stack_trace",
  "arguments": {
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

#### FR-2.4.2: Select Stack Frame
**Description:** Change the current stack frame context.

**Input:**
```json
{
  "name": "select_stack_frame",
  "arguments": {
    "frame_index": 2
  }
}
```

**Acceptance Criteria:**
- Changes debugger focus to specified frame
- Updates variables view context
- Enables evaluation in frame context

#### FR-2.4.3: List Threads
**Description:** List all threads in the debugged process.

**Input:**
```json
{
  "name": "list_threads",
  "arguments": {
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

### 2.5 Variable Inspection

#### FR-2.5.1: Get Variables
**Description:** Get variables visible in the current stack frame.

**Input:**
```json
{
  "name": "get_variables",
  "arguments": {
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

#### FR-2.5.2: Expand Variable
**Description:** Get child properties of an object variable.

**Input:**
```json
{
  "name": "expand_variable",
  "arguments": {
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

#### FR-2.5.3: Set Variable Value
**Description:** Modify the value of a variable during debugging.

**Input:**
```json
{
  "name": "set_variable",
  "arguments": {
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

### 2.6 Expression Evaluation

#### FR-2.6.1: Evaluate Expression
**Description:** Evaluate an expression in the current debug context.

**Input:**
```json
{
  "name": "evaluate_expression",
  "arguments": {
    "expression": "myVariable + 10",
    "frame_index": 0,
    "allow_side_effects": false
  }
}
```

**Output:**
```json
{
  "result": {
    "value": "52",
    "type": "int",
    "has_children": false
  },
  "error": null
}
```

**Acceptance Criteria:**
- Evaluates arbitrary expressions in frame context
- `allow_side_effects` controls method invocation
- Returns structured result with type information
- Handles evaluation errors gracefully

#### FR-2.6.2: Evaluate Code Fragment
**Description:** Execute a multi-line code fragment.

**Input:**
```json
{
  "name": "evaluate_code_fragment",
  "arguments": {
    "code": "int sum = 0;\nfor (int i : list) { sum += i; }\nreturn sum;",
    "frame_index": 0
  }
}
```

**Acceptance Criteria:**
- Supports multi-statement evaluation
- Returns result of last expression/statement
- Requires explicit `allow_side_effects: true`

---

### 2.7 Watch Management

#### FR-2.7.1: List Watches
**Description:** List all watch expressions.

**Input:**
```json
{
  "name": "list_watches"
}
```

**Output:**
```json
{
  "watches": [
    {
      "id": "watch_uuid",
      "expression": "myObject.getValue()",
      "current_value": "42",
      "error": null
    }
  ]
}
```

#### FR-2.7.2: Add Watch
**Description:** Add a watch expression.

**Input:**
```json
{
  "name": "add_watch",
  "arguments": {
    "expression": "myObject.getValue()"
  }
}
```

#### FR-2.7.3: Remove Watch
**Description:** Remove a watch expression.

**Input:**
```json
{
  "name": "remove_watch",
  "arguments": {
    "watch_id": "watch_uuid"
  }
}
```

---

### 2.8 Source Navigation

#### FR-2.8.1: Get Source Context
**Description:** Get source code around the current execution point.

**Input:**
```json
{
  "name": "get_source_context",
  "arguments": {
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
| `list_debug_sessions` | Session | P0 |
| `start_debug_session` | Session | P0 |
| `stop_debug_session` | Session | P0 |
| `get_debug_session_status` | Session | P0 |
| `list_breakpoints` | Breakpoints | P0 |
| `set_breakpoint` | Breakpoints | P0 |
| `remove_breakpoint` | Breakpoints | P0 |
| `toggle_breakpoint` | Breakpoints | P1 |
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
| `evaluate_expression` | Evaluation | P0 |
| `evaluate_code_fragment` | Evaluation | P2 |
| `list_watches` | Watches | P2 |
| `add_watch` | Watches | P2 |
| `remove_watch` | Watches | P2 |
| `get_source_context` | Navigation | P1 |

**Priority Legend:**
- P0: Must have for MVP
- P1: Important for usability
- P2: Nice to have

---

## Appendix B: Example AI Agent Workflow

```
Agent: I need to debug why the calculateTotal function returns wrong values.

1. Agent: set_breakpoint(file="/src/Calculator.java", line=42)
   → Breakpoint set at Calculator.java:42

2. Agent: start_debug_session(run_configuration="CalculatorTest")
   → Debug session started, waiting for breakpoint

3. [Breakpoint hit]

4. Agent: get_variables(scope="all")
   → Shows: items=[Item@1, Item@2], subtotal=0, tax=0

5. Agent: evaluate_expression(expression="items.stream().mapToDouble(Item::getPrice).sum()")
   → Result: 150.0

6. Agent: step_over()
   → Now at line 43

7. Agent: get_variables()
   → Shows: subtotal=150.0, tax=0

8. Agent: step_over()
   → Now at line 44, tax calculation

9. Agent: evaluate_expression(expression="subtotal * TAX_RATE")
   → Result: 0.0 (TAX_RATE is 0!)

10. Agent: "Found the bug! TAX_RATE constant is set to 0 instead of 0.08"
```

This workflow demonstrates how an AI agent can systematically debug code using the MCP debugger tools.
