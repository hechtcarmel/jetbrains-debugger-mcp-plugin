<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JetBrains Debugger MCP Plugin Changelog

## [Unreleased]

### Breaking
- **Requires IntelliJ Platform 2025.2 or newer** (was 2025.1). The MCP SDK cannot be linked on 2025.1: it reaches `kotlin.time.Clock`, which that platform's bundled Kotlin standard library does not ship, and its Ktor version needs a coroutines API that 2025.1 does not have either.
- **`initialize` now negotiates the protocol version** instead of reporting a fixed one per transport. A client that asks for `2024-11-05` is answered in `2024-11-05`; previously the streamable endpoint always claimed `2025-03-26` and the legacy endpoint always claimed `2024-11-05`, whatever the client requested.
- **The server description moved from `serverInfo.description` to `instructions`.** `description` was never part of the MCP specification; `instructions` is where the spec carries this text.
- **Calling an unknown tool now returns a normal result with `isError: true`** rather than a JSON-RPC protocol error, and the message no longer carries the doubled `Method not found: Tool not found:` prefix. This matches the error contract the plugin already documented, and unlike a transport error it is something a model can read and act on.
- **`GET /debugger-mcp/streamable-http` now opens a server-to-client SSE stream** instead of returning `405`. This is what enables notifications, progress and cancellation.
- **Input schemas no longer declare `additionalProperties: false`.** The MCP SDK's schema type cannot express it, so unrecognised arguments are no longer rejected by validating clients.
- Malformed input is classified slightly differently: an empty body is `400` on every endpoint (the stateless endpoint previously answered `200`), a JSON object that is not a JSON-RPC message is a parse error (`-32700`) rather than an invalid request (`-32600`), an empty batch is accepted with nothing to answer rather than rejected, and batching `initialize` is no longer refused outright.
- A request to the streamable endpoint without a session is still refused, but as `-32000 "Server not initialized"` rather than `-32600 "Missing Mcp-Session-Id header"`.
- A notification is still answered `202`, but the body is now a JSON `null` literal rather than empty.
- An `initialize` requesting a protocol version the server does not know is answered with the newest supported version (`2025-11-25`) instead of a fixed constant.
- `Accept` and `Content-Type` remain advisory on the POST endpoints, as before: the SDK's strict header validation is relaxed at the edge so `curl`-style requests (wildcard `Accept`, implicit `Content-Type`) keep working.

### Changed
- **The hand-rolled MCP protocol layer has been replaced by the official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk).** JSON-RPC framing, `initialize`, capability and version negotiation, `ping`, session lifecycle, batching and all three transports are now the SDK's. About 1,200 lines of protocol code were deleted. Endpoint URLs, the 23 tool names, parameters, descriptions and result shapes, and the `isError` error contract are unchanged (the one schema-level change is `additionalProperties`, above), so existing client configuration keeps working.
- The plugin no longer bundles a Ktor HTTP *client*, which removes a latent `NoSuchMethodError` that shipped in previous releases.

### Added
- **Breakpoint `condition` and `log_message` expressions now pass through the Evaluate Expression safety guard.** Previously they were handed to the debugger unchecked, so a blocked operation could be smuggled past Read-only mode as a breakpoint condition.
- `wait_for_pause`, `get_debug_session_status`: `stackSummary` now honours `max_stack_frames` with real frames, `totalStackDepth` is the real reported depth, `currentThread` is the actual thread name (was hardcoded `main`), and `threadCount` is the real thread count (was hardcoded 1).
- Tool arguments are validated at the boundary: a wrong type (`line: "42"`), an out-of-range value (`frame_index: -1`) or an unknown enum value (`suspend_policy: "banana"`) now returns a clear typed error instead of being silently coerced, defaulted, or surfacing a raw exception.
- Tool-window actions are registered with the platform (visible in Find Action, bindable in the keymap) and a **Tools → Debugger MCP Server** menu was added. Status icons now have dark-theme variants.
- `SECURITY.md` (honest threat model) and `CONTRIBUTING.md`.

### Fixed
- Stack-frame `presentation` strings no longer disagree with the `line` field by one: they are now built from the same source position as the machine-readable fields instead of the platform's internal `toString()`, which encodes a 0-based line (live-QA finding 4.1).
- A `suspend_policy: "none"` tracepoint is never reported as `breakpointHit` — it logs without suspending, so it cannot be the cause of a pause. Stepping onto a tracepoint's line previously misattributed the pause to it (live-QA finding 4.2).
- Tools called without a `session_id` now fall back to the only live session when the IDE has no "current" one. A session that is running and has never paused is not "current", so `pause_execution` against it answered `No active debug session` even with exactly one session running (live-QA §5).
- **`set_breakpoint` uses the breakpoint-manager API directly** instead of a UI toggle plus a fixed 100 ms sleep. Setting the same line twice now updates the existing breakpoint instead of deleting it, concurrent calls are safe, the intermittent "Failed to create breakpoint" under load is gone, and the call is faster.
- **Tool calls no longer hang while a modal dialog is open in the IDE.** Execution-control and breakpoint tools used `invokeAndWait` with the default modality, which queued behind any open dialog; `wait_for_pause`'s auto-resume had the same defect.
- **Deep stack traces no longer break every stack operation**: the frame collector resumed its continuation once per debugger batch, throwing `Already resumed` on the debugger thread for any stack deep enough to arrive in two batches.
- **`pausedReason` and `breakpointHit` are computed from the pause site**, not the currently selected frame — `select_stack_frame` no longer changes what breakpoint the status reports — and disabled or muted breakpoints are no longer reported as hit.
- The Evaluate Expression safety guard **rejects** interpolated string templates (Kotlin `${...}`, JS backticks, Python f-strings, Ruby `#{}`), unterminated string literals and over-length expressions instead of blanking them — blanking let `"${Runtime.getRuntime().exec(cmd)}"` pass the strictest mode.
- `log_message` breakpoints in Rust/Go/Swift/C/C++ files are rejected with a clear error at `set_breakpoint` instead of silently storing Java-syntax expressions those debuggers cannot evaluate; multi-part Java log messages starting with an expression now concatenate as strings instead of adding integers.
- Session and breakpoint ids are stable UUIDs instead of JVM `hashCode()` values, which could collide and make `remove_breakpoint` or `session_id` lookups ambiguous.
- The MCP server binds its port when the IDE finishes starting up, not when anything first touches the service — opening the tool window or settings no longer starts the server as a side effect, and the "server started" notification appears once per IDE session instead of on every project open.
- The version reported to MCP clients is read from the installed plugin descriptor at runtime, so it can never drift from the shipped version again.
- The tool window releases its listeners when closed (it previously leaked the project through an unregistered disposable).


## [4.4.0] - 2026-08-01

### Fixed

- `set_variable` now applies the Evaluate Expression safety mode to `new_value`. It previously evaluated the value as a code fragment without consulting the safety guard at all, so a blocked operation could be run by passing it as a variable value instead of an expression — bypassing Read-only mode and the risky-operation blocklist entirely.
- `get_variables`, `get_debug_session_status` and `wait_for_pause` now declare every field they actually return in their output schemas. `wait_for_pause` and `get_debug_session_status` each omitted `breakpointHit`, `totalStackDepth`, `currentThread` and `threadCount`; `get_variables` omitted `scope`. MCP clients that validate `structuredContent` against the declared schema would reject those responses.
- The tool window's **Refresh** button now works. It searched for the panel as the tool window's content component, but the content is a wrapper panel, so the button did nothing at all.
- The server version reported to MCP clients during `initialize` was pinned at `4.0.0` while the plugin shipped 4.3.x. It now tracks the real plugin version, and a test keeps the two in sync.

### Changed

- Test suite rebuilt around golden contracts for the client-facing surface (23 tool schemas, 31 result-model wire shapes) and conformance tests that drive the real server over HTTP. Removed ~1,850 lines of tests that could not fail for any production reason, along with two dead source files (`util/JsonUtils.kt`, `util/ProjectUtils.kt`) that had no callers.
- `CLAUDE.md` no longer documents the structured error codes `session_not_found`, `session_not_paused`, `breakpoint_not_found`, `invalid_location` and `evaluation_error`. No tool has ever emitted them; the real error contract is documented in their place, along with the suite's known gaps.

## [4.3.1]

### Fixed

- `get_variables`, `evaluate_expression`, `set_variable`, `get_debug_session_status`, and `wait_for_pause` no longer return the localized "Collecting data..." placeholder (or duplicate entries) - the plugin now waits for the debugger's asynchronous value presentation ([#51](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/issues/51))
- `set_breakpoint`, `run_to_line`, and `get_source_context` now accept files inside JAR archives using the `!/` separator (e.g. `/path/to/lib-sources.jar!/com/example/Foo.kt`) ([#51](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/issues/51))

## [4.3.0] - 2026-05-22

### Added

- Added configurable safety controls for `evaluate_expression`, including a default risky-operation blocklist, read-only mode, and optional custom regex block rules.

## [4.2.0] - 2026-04-02

### Added

- **`wait_for_pause` tool** - Blocks until a debug session pauses (breakpoint, exception, or manual pause) and returns the full session status. Eliminates manual polling loops after `resume_execution` or `start_debug_session`. Supports optional `breakpoint_ids` filter to wait for specific breakpoints.
- **Automatic session discovery** - `wait_for_pause` can be called immediately after `start_debug_session` without needing to poll for the session ID first.

## [4.1.0] - 2026-03-14

### Added

- **Companion skill** - "Get Companion Skill" button in the tool window toolbar that guides AI coding agents on when and how to use the debugger MCP tools effectively
  - Install directly to project's `.claude/skills/` directory for Claude Code
  - Export as `.skill` or `.zip` file for sharing or manual installation

## [4.0.0] - 2026-03-11

### Added

- **Streamable HTTP transport** - New primary MCP transport (2025-03-26 spec) at `/debugger-mcp/streamable-http`

### Changed

- **Default transport is now Streamable HTTP** - All client install/copy commands updated:
  - Claude Code: `--transport http` (was `--transport sse`)
  - Codex CLI: `--url` with native Streamable HTTP (was `mcp-remote` bridge)
  - Gemini CLI: `httpUrl` field for native Streamable HTTP (was `mcp-remote` command)
- **Protocol version** - Default MCP protocol version is now `2025-03-26` (was `2024-11-05`)
- **Generic config options** - "Standard SSE" / "Via mcp-remote" renamed to "Streamable HTTP" / "Legacy SSE"

### Removed

- `ktor-server-cors` dependency (replaced by manual CORS handling)

## [3.4.0] - 2026-02-19

### Added

- **Tool window footer links** - GitHub, IDE Index MCP Server, and Buy Me a Coffee links in the toolbar for quick access
  - "Star/Report Issues" link to the GitHub repository
  - "Try IDE Index MCP Server" link to the companion plugin on JetBrains Marketplace
  - "Buy Me a Coffee" link to support the developer

## [3.3.1] - 2026-02-12

### Fixed

- **EDT thread safety** - Fixed `select_stack_frame` and `stop_debug_session` tools crashing with `RuntimeExceptionWithAttachments` when called from a background thread. Both now correctly dispatch to the Event Dispatch Thread.

## [3.3.0] - 2026-02-12

### Added

- **Configurable server bind address** - New "Server host" setting in Settings > Tools > Debugger MCP Server
  - Editable combo box with `127.0.0.1` (localhost only, default) and `0.0.0.0` (all interfaces) presets
  - Enables remote debugging in containers, remote dev environments, and multi-machine setups

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

- HTTP+SSE transport for MCP communication
- SSE endpoint at `/debugger-mcp/sse` for server-sent events
- POST endpoint at `/debugger-mcp` for JSON-RPC request handling
- Full MCP protocol support (initialize, tools/list, tools/call)
- Tool annotations for behavior hints (readOnlyHint, destructiveHint, idempotentHint)
- Output schema support for structured tool responses

### Technical Details

- Built on IntelliJ Platform SDK 2025.1+
- Uses XDebugger API for debugger integration
- Kotlin coroutines for async operations
- kotlinx.serialization for JSON handling
- MCP protocol version 2024-11-05
- Compatible with all JetBrains IDEs that support XDebugger (IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, Rider, Android Studio)

[Unreleased]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v4.4.0...HEAD
[4.4.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v4.3.1...v4.4.0
[4.3.1]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v4.3.0...v4.3.1
[4.3.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v4.2.0...v4.3.0
[4.2.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v4.1.0...v4.2.0
[4.1.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v4.0.0...v4.1.0
[4.0.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.4.0...v4.0.0
[3.4.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.3.1...v3.4.0
[3.3.1]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.3.0...v3.3.1
[3.3.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.2.0...v3.3.0
[3.2.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.1.1...v3.2.0
[3.1.1]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.1.0...v3.1.1
[3.1.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.0.1...v3.1.0
[3.0.1]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v2.0.0...v3.0.0
[2.0.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.3.1...v2.0.0
[1.3.1]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.2.2...v1.3.0
[1.2.2]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/commits/v1.0.0
