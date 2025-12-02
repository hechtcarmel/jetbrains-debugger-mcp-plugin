# JetBrains Debugger MCP Plugin

![Build](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

Give AI agents full debugger control through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io).
Set breakpoints, step through code, inspect variables, and evaluate expressions—all driven by your AI coding assistant.

<!-- Plugin description -->
**Give AI agents full debugger control** through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io).
Set breakpoints, step through code, inspect variables, and evaluate expressions—all driven by your AI coding assistant.

### Debug Session Management
- **Start/Stop Debug Sessions** - Launch any run configuration in debug mode
- **Session Status** - Get comprehensive debug state in a single call including variables, watches, stack trace, and source context
- **Multi-Session Support** - Manage multiple concurrent debug sessions

### Breakpoint Management
- **Line Breakpoints** - Set, remove, and list breakpoints at any location
- **Conditional Breakpoints** - Add conditions that must be true to pause execution
- **Exception Breakpoints** - Break on caught or uncaught exceptions
- **Tracepoints** - Log messages without pausing execution

### Execution Control
- **Step Over/Into/Out** - Navigate through code line by line
- **Resume & Pause** - Control execution flow
- **Run to Line** - Continue execution until a specific line

### Variable Inspection
- **View Variables** - Inspect local variables, arguments, and object fields
- **Expand Objects** - Drill into complex data structures
- **Modify Values** - Change variable values during debugging
- **Watch Expressions** - Monitor expressions across debug steps

### Expression Evaluation
- **Evaluate Expressions** - Run arbitrary expressions in the current context
- **Code Fragments** - Execute multi-line code snippets

### Stack & Thread Navigation
- **Stack Traces** - View the complete call stack with source locations
- **Frame Selection** - Switch context to any stack frame
- **Thread Listing** - See all threads and their states

### Why This Plugin?
- **AI-Driven Debugging** - Let your AI assistant debug code autonomously
- **Rich Context** - Single-call status provides variables, stack, watches, and source context
- **Multi-IDE Support** - Works with any JetBrains IDE that has XDebugger
- **26 Tools** - Complete debugging capability through MCP

### Supported AI Assistants
- Claude Code (CLI)
- Claude Desktop
- Cursor
- Windsurf
- VS Code with MCP extension
- Any MCP-compatible client
<!-- Plugin description end -->

## Quick Start

1. Install plugin and restart IDE
2. Open **Debugger MCP Server** tool window (bottom panel)
3. Click **"Install on Coding Agents"**
4. Select your AI assistant and follow the instructions
5. Ask your AI to set breakpoints and start debugging!

## Example Workflow

Just tell your AI:

> "Debug the calculateTotal function—set a breakpoint at line 42, run the tests in debug mode, and show me the variable values when it pauses."

## Available Tools (26 total)

### Run Configuration Tools
| Tool | Description |
|------|-------------|
| `list_run_configurations` | List all available run configurations |
| `run_configuration` | Start a run configuration in debug or run mode |

### Debug Session Tools
| Tool | Description |
|------|-------------|
| `list_debug_sessions` | List all active debug sessions |
| `start_debug_session` | Start a new debug session |
| `stop_debug_session` | Stop a debug session |
| `get_debug_session_status` | Get comprehensive session status |

### Breakpoint Tools
| Tool | Description |
|------|-------------|
| `list_breakpoints` | List all breakpoints |
| `set_breakpoint` | Set a line breakpoint |
| `remove_breakpoint` | Remove a breakpoint |
| `set_exception_breakpoint` | Set an exception breakpoint |

### Execution Control Tools
| Tool | Description |
|------|-------------|
| `resume` | Resume execution |
| `pause` | Pause execution |
| `step_over` | Step over to next line |
| `step_into` | Step into function call |
| `step_out` | Step out of current function |
| `run_to_line` | Run to a specific line |

### Stack & Thread Tools
| Tool | Description |
|------|-------------|
| `get_stack_trace` | Get current stack trace |
| `select_stack_frame` | Select a stack frame |
| `list_threads` | List all threads |

### Variable Tools
| Tool | Description |
|------|-------------|
| `get_variables` | Get variables in current frame |
| `expand_variable` | Expand a composite variable |
| `set_variable` | Modify a variable value |

### Watch Tools
| Tool | Description |
|------|-------------|
| `add_watch` | Add a watch expression |
| `remove_watch` | Remove a watch expression |

### Navigation Tools
| Tool | Description |
|------|-------------|
| `get_source_context` | Get source code around a location |

### Evaluation Tools
| Tool | Description |
|------|-------------|
| `evaluate` | Evaluate an expression |

## Installation

### Using the IDE built-in plugin system

<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Debugger MCP Server"</kbd> > <kbd>Install</kbd>

### Manual Installation

Download the [latest release](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/releases/latest) and install it manually using:

<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Configuration

### Settings

Access plugin settings via <kbd>Settings/Preferences</kbd> > <kbd>Tools</kbd> > <kbd>Debugger MCP Server</kbd>

- **Server Port**: Set a specific port (0 = auto-assign)
- **Max History Size**: Number of commands to keep in history
- **Auto-scroll**: Automatically scroll to latest commands
- **Show Notifications**: Enable/disable notifications

### AI Assistant Configuration

The plugin provides one-click installation for popular AI assistants:

1. Open the **Debugger MCP Server** tool window
2. Click **"Install on Coding Agents"**
3. Choose your assistant:
   - **Claude Code**: Automatic CLI installation
   - **Claude Desktop**: Copy configuration to clipboard
   - **Cursor**: Copy configuration to clipboard
   - **VS Code**: Copy configuration to clipboard
   - **Windsurf**: Copy configuration to clipboard

## Development

### Building

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

### Running in IDE

```bash
./gradlew runIde
```

## Architecture

The plugin uses HTTP+SSE transport for MCP communication:

- **SSE Endpoint**: `/debugger-mcp/sse` - Server-sent events for connection
- **POST Endpoint**: `/debugger-mcp` - JSON-RPC request handling

## License

Apache License 2.0

---

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
