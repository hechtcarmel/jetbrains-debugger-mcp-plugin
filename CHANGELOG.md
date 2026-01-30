<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JetBrains Debugger MCP Plugin Changelog

## [Unreleased]

## [3.2.0] - 2026-01-30

### Changed
- **2026.1 compatibility** - Replaced internal `XExpressionImpl.fromText()` API with public `XDebuggerUtil.createExpression()` for forward compatibility with the JetBrains 2026.1 debugger architecture redesign
- Added 2026.1 to plugin verification targets

## [3.1.1] - 2026-01-22

### Fixed
- **Log message expressions now work** - Fixed issue where `log_message` with `{expression}` syntax didn't evaluate variables. The plugin now automatically transforms user-friendly `{expr}` placeholders to language-specific expressions.

## [3.1.0] - 2026-01-07

### Added
- Codex CLI install option in the "Install on Coding Agents" popup (remove then reinstall)

## [3.0.1] - 2025-12-28

### Changed
- **Claude Code install command** - Now removes legacy `jetbrains-debugger` name in addition to the current IDE-specific name, ensuring clean upgrades from v1.x
- **Agent rule** - Copied rule now uses IDE-specific server name (e.g., `intellij-debugger`) instead of hardcoded `jetbrains-debugger`
- **Documentation** - Updated README with IDE-specific server names for all supported IDEs

## [3.0.0] - 2025-12-24

### Fixed
- **MCP spec compliance** - `notifications/initialized` now handled correctly per MCP specification
  - Method renamed from `initialized` to `notifications/initialized` (per spec)
  - Notifications no longer receive a response (spec: "receiver MUST NOT send a response")

### Breaking
- **Claude Code transport type** - Changed `--transport http` to `--transport sse` in generated install commands

## [2.0.0] - 2025-12-16

### Added
- **Configurable server port** with IDE-specific defaults (e.g., IntelliJ: 29190, PyCharm: 29192)
- **IDE-specific server names** (e.g., `intellij-debugger`, `pycharm-debugger`) to run multiple IDEs simultaneously
- **Port conflict detection** with error notification and settings link
- **Settings shortcut** - "Change port" link in toolbar

### Changed
- **Breaking**: Migrated to custom Ktor CIO server - update MCP client configs with new port/name
- Server URL no longer depends on IDE's built-in server port (was 63342)

## [1.3.1] - 2025-12-10

### Changed
- Replace `localhost` with `127.0.0.1` in server URLs for consistency and compatibility

## [1.3.0] - 2025-12-07

### Fixed
- **Rust/Cargo debug support** - Fixed debug session startup for Rust projects in RustRover. Changed from `ExecutionEnvironmentBuilder.restartRunProfile()` to `ProgramRunnerUtil.executeConfiguration()` which properly handles toolchain context resolution.

### Changed
- **Improved tool descriptions** - Added language-specific limitation notes to `set_variable` and `evaluate_expression` tools. Native debuggers (LLDB/GDB) used for Rust, C++, and Go have limited support for modifying complex types and calling methods during debugging.

## [1.2.2] - 2025-12-07

### Changed
- **Simplified settings panel** - Removed unused settings that had no effect on plugin behavior.

## [1.2.1] - 2025-12-05

### Fixed
- **Parameter naming consistency** - Fixed mismatch between schema (`project_path`) and handler (`projectPath`). All tools now consistently use `project_path` as the parameter name for specifying the project root path.

## [1.2.0] - 2025-12-04

### Added
- **Full SSE transport support** - Responses are now sent via SSE `message` events per MCP spec (2024-11-05)
- **MCP Inspector compatibility** - Works correctly with `npx @modelcontextprotocol/inspector` in SSE mode
- **Dual transport support** - Supports both SSE transport and Streamable HTTP transport simultaneously

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
- Settings panel for command history size configuration

### Technical Details
- Built on IntelliJ Platform SDK 2025.1+
- Uses XDebugger API for debugger integration
- Kotlin coroutines for async operations
- kotlinx.serialization for JSON handling
- MCP protocol version 2024-11-05
- Compatible with all JetBrains IDEs that support XDebugger (IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, Rider, Android Studio)

**Total Tools: 22**
