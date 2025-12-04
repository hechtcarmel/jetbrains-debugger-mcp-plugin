# Debugger MCP Server

![Build](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/29233.svg)](https://plugins.jetbrains.com/plugin/29233)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/29233.svg)](https://plugins.jetbrains.com/plugin/29233)

A JetBrains IDE plugin that exposes an **MCP (Model Context Protocol) server**, giving AI coding assistants full programmatic control over the debugger. Set breakpoints, step through code, inspect variables, and evaluate expressions—all driven autonomously by your AI assistant.

**Works with all JetBrains IDEs**: IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, Rider, and Android Studio.

<!-- Plugin description -->
**Debugger MCP Server** gives AI coding assistants complete control over the IDE's debugger through the Model Context Protocol (MCP). Let your AI assistant debug code autonomously—from setting breakpoints to inspecting variables to stepping through execution.

### Features

**Debug Session Management**
- **Start/Stop Sessions** - Launch any run configuration in debug mode
- **Rich Status** - Get comprehensive state in a single call (variables, stack, source context)
- **Multi-Session Support** - Manage multiple concurrent debug sessions

**Breakpoint Management**
- **Line Breakpoints** - Set breakpoints at any valid location
- **Conditional Breakpoints** - Add conditions that must be true to pause
- **Tracepoints** - Log messages without pausing execution

**Execution Control**
- **Step Over/Into/Out** - Navigate through code line by line
- **Resume & Pause** - Control execution flow
- **Run to Line** - Continue execution until a specific line

**Variable Inspection**
- **View Variables** - Inspect local variables, arguments, and object fields
- **Modify Values** - Change variable values during debugging

**Expression Evaluation**
- **Evaluate Expressions** - Run arbitrary expressions in the current context
- **Code Fragments** - Execute multi-line code snippets

**Stack & Thread Navigation**
- **Stack Traces** - View the complete call stack with source locations
- **Frame Selection** - Switch context to any stack frame
- **Thread Listing** - See all threads and their states

### Why Use This Plugin?

Unlike manual debugging, this plugin enables:
- **Autonomous AI Debugging** - Your AI assistant can debug code without human intervention
- **Rich Context in Single Calls** - Get variables, stack, and source in one request
- **Programmatic Breakpoint Control** - Set conditional breakpoints with complex expressions
- **Cross-IDE Compatibility** - Works with any JetBrains IDE that supports XDebugger
- **22 Comprehensive Tools** - Full debugging capability through MCP

Perfect for AI-assisted development workflows where you want your assistant to investigate bugs, validate fixes, or explore code behavior autonomously.
<!-- Plugin description end -->

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Client Configuration](#client-configuration)
- [Available Tools](#available-tools)
- [Multi-Project Support](#multi-project-support)
- [Tool Window](#tool-window)
- [Error Codes](#error-codes)
- [Settings](#settings)
- [Requirements](#requirements)
- [Contributing](#contributing)

## Installation

### Using the IDE built-in plugin system

<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Debugger MCP Server"</kbd> > <kbd>Install</kbd>

### Using JetBrains Marketplace

Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29233) and install it by clicking the <kbd>Install to ...</kbd> button.

### Manual Installation

Download the [latest release](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/releases/latest) and install it manually:
<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Quick Start

1. **Install the plugin** and restart your JetBrains IDE
2. **Open a project** - the MCP server starts automatically
3. **Find your IDE port**: <kbd>Settings</kbd> > <kbd>Build, Execution, Deployment</kbd> > <kbd>Debugger</kbd> > <kbd>Built-in Server Port</kbd> (default: 63342)
4. **Configure your AI assistant** with the server URL: `http://localhost:{PORT}/debugger-mcp/sse`
5. **Use the tool window** (bottom panel: "Debugger MCP Server") to copy configuration or monitor commands

### Using the "Install on Coding Agents" Button

The easiest way to configure your AI assistant:
1. Open the "Debugger MCP Server" tool window (bottom panel)
2. Click the prominent **"Install on Coding Agents"** button on the right side of the toolbar
3. A popup appears with two sections:
   - **Install Now** - For Claude Code CLI: Runs the installation command automatically
   - **Copy Configuration** - For other clients: Copies the JSON config to your clipboard
4. For "Copy Configuration" clients, paste the config into the appropriate config file

## Example Workflow

Just tell your AI assistant:

> "Debug the calculateTotal function—set a breakpoint at line 42, run the tests in debug mode, and show me the variable values when it pauses."

Or for more complex debugging:

> "There's a bug in UserService. Set a breakpoint at line 42, run the tests in debug mode, and when it breaks, show me the stack trace and all local variables."

## Client Configuration

### Claude Code (CLI)

Run this command in your terminal:

```bash
claude mcp add --transport http jetbrains-debugger http://localhost:63342/debugger-mcp/sse --scope user
```

Options:
- `--scope user` - Adds globally for all projects
- `--scope project` - Adds to current project only

To remove: `claude mcp remove jetbrains-debugger`

### Cursor

Add to `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally:

```json
{
  "mcpServers": {
    "jetbrains-debugger": {
      "url": "http://localhost:63342/debugger-mcp/sse"
    }
  }
}
```

### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "jetbrains-debugger": {
      "serverUrl": "http://localhost:63342/debugger-mcp/sse"
    }
  }
}
```

### VS Code (Generic MCP)

```json
{
  "mcp.servers": {
    "jetbrains-debugger": {
      "transport": "sse",
      "url": "http://localhost:63342/debugger-mcp/sse"
    }
  }
}
```

> **Note**: Replace `63342` with your actual IDE port if different. Check <kbd>Settings</kbd> > <kbd>Debugger</kbd> > <kbd>Built-in Server Port</kbd>.

## Available Tools

The plugin provides **22 MCP tools** organized by category:

### Run Configuration Tools

| Tool | Description |
|------|-------------|
| `list_run_configurations` | List all available run configurations in the project |
| `execute_run_configuration` | Execute a run configuration in debug or run mode |

### Debug Session Tools

| Tool | Description |
|------|-------------|
| `list_debug_sessions` | List all active debug sessions with state and metadata |
| `start_debug_session` | Start a new debug session for a run configuration |
| `stop_debug_session` | Stop/terminate a debug session |
| `get_debug_session_status` | Get comprehensive status (variables, stack, source) in one call |

### Breakpoint Tools

| Tool | Description |
|------|-------------|
| `list_breakpoints` | List all breakpoints with optional filtering |
| `set_breakpoint` | Set a line breakpoint with condition, log message, suspend policy |
| `remove_breakpoint` | Remove a breakpoint by ID or location |

### Execution Control Tools

| Tool | Description |
|------|-------------|
| `resume_execution` | Resume paused execution |
| `pause_execution` | Pause running execution |
| `step_over` | Step over to next line (without entering methods) |
| `step_into` | Step into method calls |
| `step_out` | Step out of current method |
| `run_to_line` | Continue execution until a specific line |

### Stack & Thread Tools

| Tool | Description |
|------|-------------|
| `get_stack_trace` | Get current call stack with file/line/method info |
| `select_stack_frame` | Change debugger context to a different stack frame |
| `list_threads` | List all threads with state information |

### Variable Tools

| Tool | Description |
|------|-------------|
| `get_variables` | Get all variables visible in current stack frame |
| `set_variable` | Modify a variable's value during debugging |

### Navigation Tools

| Tool | Description |
|------|-------------|
| `get_source_context` | Get source code around current execution point |

### Evaluation Tools

| Tool | Description |
|------|-------------|
| `evaluate_expression` | Evaluate an expression or code fragment in debug context |

> **Note**: For detailed tool documentation with parameters, examples, and response formats, see [USAGE.md](USAGE.md).

## Multi-Project Support

When multiple projects are open in a single IDE window, you must specify which project to use with the `project_path` parameter:

```json
{
  "name": "set_breakpoint",
  "arguments": {
    "project_path": "/Users/dev/myproject",
    "file_path": "/Users/dev/myproject/src/Main.java",
    "line": 42
  }
}
```

If `project_path` is omitted:
- **Single project open**: That project is used automatically
- **Multiple projects open**: An error is returned with the list of available projects

## Tool Window

The plugin adds a "Debugger MCP Server" tool window (bottom panel) that shows:

- **Server Status**: Running indicator with server URL and port
- **Project Name**: Currently active project
- **Command History**: Log of all MCP tool calls with:
  - Timestamp
  - Tool name
  - Status (Success/Error/Pending)
  - Parameters and results (expandable)
  - Execution duration

### Tool Window Actions

| Action | Description |
|--------|-------------|
| Refresh | Refresh server status and command history |
| Copy URL | Copy the MCP server URL to clipboard |
| Clear History | Clear the command history |
| Export History | Export history to JSON or CSV file |
| **Install on Coding Agents** | Install MCP server on AI assistants (prominent button on right) |

## Error Codes

### JSON-RPC Standard Errors

| Code | Name | Description |
|------|------|-------------|
| -32700 | Parse Error | Failed to parse JSON-RPC request |
| -32600 | Invalid Request | Invalid JSON-RPC request format |
| -32601 | Method Not Found | Unknown method name |
| -32602 | Invalid Params | Invalid or missing parameters |
| -32603 | Internal Error | Unexpected internal error |

### Custom MCP Errors

| Code | Name | Description |
|------|------|-------------|
| -32001 | Session Not Found | Debug session not found |
| -32002 | File Not Found | Specified file does not exist |
| -32003 | Not Paused | Operation requires paused session |
| -32004 | Breakpoint Error | Failed to set/remove breakpoint |
| -32005 | Evaluation Error | Expression evaluation failed |

## Settings

Configure the plugin at <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Debugger MCP Server</kbd>:

| Setting | Default | Description |
|---------|---------|-------------|
| Server Port | 0 (auto) | Set specific port or 0 for auto-assign |
| Max History Size | 100 | Maximum number of commands to keep in history |
| Auto-scroll | true | Auto-scroll to new commands in history |
| Show Notifications | true | Show notifications for server events |

## Requirements

- **JetBrains IDE** 2025.1 or later (any IDE based on IntelliJ Platform)
- **JVM** 21 or later
- **MCP Protocol** 2024-11-05

### Supported IDEs

All JetBrains IDEs with XDebugger support:

| IDE | Supported |
|-----|-----------|
| IntelliJ IDEA Community/Ultimate | Yes |
| Android Studio | Yes |
| PyCharm Community/Professional | Yes |
| WebStorm | Yes |
| GoLand | Yes |
| PhpStorm | Yes |
| RubyMine | Yes |
| CLion | Yes |
| Rider | Yes |
| DataGrip | No (no debugger) |

## Architecture

The plugin supports **dual MCP transports** on the IDE's built-in web server:

### SSE Transport (MCP Inspector, spec-compliant clients)

```
AI Assistant ──────► GET /debugger-mcp/sse           (establish SSE stream)
                     ◄── event: endpoint             (receive POST URL with sessionId)
             ──────► POST /debugger-mcp?sessionId=x  (JSON-RPC requests)
                     ◄── HTTP 202 Accepted
                     ◄── event: message              (JSON-RPC response via SSE)
```

### Streamable HTTP Transport (Claude Code, simple clients)

```
AI Assistant ──────► POST /debugger-mcp              (JSON-RPC requests)
                     ◄── JSON-RPC response           (immediate HTTP response)
```

This dual approach:
- **MCP Inspector compatible** - Full SSE transport per MCP spec (2024-11-05)
- **Claude Code compatible** - Streamable HTTP for simple request/response
- Requires no additional ports or processes
- Works with any MCP-compatible client
- Automatically adapts to the IDE's server port

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `./gradlew test`
5. Submit a pull request

### Development Setup

```bash
# Build the plugin
./gradlew build

# Run IDE with plugin installed
./gradlew runIde

# Run tests
./gradlew test

# Run plugin verification
./gradlew runPluginVerifier
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
