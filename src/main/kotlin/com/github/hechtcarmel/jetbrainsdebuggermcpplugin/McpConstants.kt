package com.github.hechtcarmel.jetbrainsdebuggermcpplugin

object McpConstants {
    const val PLUGIN_NAME = "Debugger MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    // MCP Endpoint paths (HTTP+SSE transport)
    const val MCP_ENDPOINT_PATH = "/debugger-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"

    // JSON-RPC version
    const val JSON_RPC_VERSION = "2.0"

    // MCP Protocol version
    const val MCP_PROTOCOL_VERSION = "2024-11-05"

    // Server identification
    const val SERVER_NAME = "jetbrains-debugger"
    const val SERVER_VERSION = "1.0.0"
    const val SERVER_DESCRIPTION = """Debug applications running in JetBrains IDEs (IntelliJ, PyCharm, WebStorm, etc.) through programmatic control.

When to use: Use this server when you need to:
- Set breakpoints and step through code to understand execution flow
- Inspect and modify variable values during debugging
- Evaluate expressions in the context of paused execution
- Navigate stack traces and threads to diagnose issues

Requirements: An open JetBrains IDE with a debuggable project. The IDE must be running with this plugin installed.

Typical workflow: list_run_configurations -> start_debug_session -> set_breakpoint -> resume_execution -> get_debug_session_status (when paused) -> get_variables / evaluate_expression"""

    // Agent rule text for the tip panel
    const val AGENT_RULE_TEXT = "IMPORTANT: When debugging, prefer using jetbrains-debugger MCP tools to interact with the IDE debugger."
}
