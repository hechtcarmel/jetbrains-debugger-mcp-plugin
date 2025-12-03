<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JetBrains Debugger MCP Plugin Changelog

## [Unreleased]

## [1.1.0] - 2025-12-03

### Added
- **Gemini CLI support** - Copy configuration for Google's Gemini CLI
- **Generic MCP Config section** - New popup section with two options:
  - Standard SSE config for clients with native SSE support
  - mcp-remote config for clients without SSE support (uses npx mcp-remote bridge)

### Changed
- Streamlined "Install on Coding Agents" popup with cleaner organization
- Simplified client list to Claude Code, Gemini CLI, and Cursor

### Removed
- VS Code and Windsurf client configurations (use Generic MCP Config instead)

## [1.0.0] - 2025-12-02

### Added

#### MCP Server
- HTTP+SSE transport for MCP communication
- SSE endpoint at `/debugger-mcp/sse` for server-sent events
- POST endpoint at `/debugger-mcp` for JSON-RPC request handling
- Full MCP protocol support (initialize, tools/list, tools/call)
- Tool annotations for behavior hints (readOnlyHint, destructiveHint, idempotentHint)
- Output schema support for structured tool responses

#### Run Configuration Tools (2 tools)
- `list_run_configurations` - List all available run configurations
- `execute_run_configuration` - Execute a run configuration in debug or run mode

#### Debug Session Tools (4 tools)
- `list_debug_sessions` - List all active debug sessions
- `start_debug_session` - Start a new debug session
- `stop_debug_session` - Stop a debug session
- `get_debug_session_status` - Get comprehensive session status with variables, stack trace, and source context

#### Breakpoint Tools (3 tools)
- `list_breakpoints` - List all breakpoints in the project
- `set_breakpoint` - Set a line breakpoint with optional condition, log message, and suspend policy
- `remove_breakpoint` - Remove a breakpoint by ID

#### Execution Control Tools (6 tools)
- `resume_execution` - Resume execution from a paused state
- `pause_execution` - Pause execution
- `step_over` - Step over to next line
- `step_into` - Step into function call
- `step_out` - Step out of current function
- `run_to_line` - Run to a specific line

#### Stack & Thread Tools (3 tools)
- `get_stack_trace` - Get current stack trace with source locations
- `select_stack_frame` - Select a stack frame by index
- `list_threads` - List all threads and their states

#### Variable Tools (2 tools)
- `get_variables` - Get variables in current frame
- `set_variable` - Modify a variable value at runtime

#### Navigation Tools (1 tool)
- `get_source_context` - Get source code around a location

#### Evaluation Tools (1 tool)
- `evaluate_expression` - Evaluate an expression or code fragment in the current context

#### GUI Components
- Debugger MCP Server tool window with server status and controls
- Command history view with filtering and export
- One-click installation for AI coding assistants
- Agent rule tip panel for easy configuration
- Settings panel for history size, auto-scroll, and notifications

### Technical Details
- Built on IntelliJ Platform SDK 2025.1+
- Uses XDebugger API for debugger integration
- Kotlin coroutines for async operations
- kotlinx.serialization for JSON handling
- MCP protocol version 2024-11-05
- Compatible with all JetBrains IDEs that support XDebugger (IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, Rider, Android Studio)

**Total Tools: 22**
