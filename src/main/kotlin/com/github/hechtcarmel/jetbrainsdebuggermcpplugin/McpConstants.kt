package com.github.hechtcarmel.jetbrainsdebuggermcpplugin

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.IdeProductInfo
import com.intellij.util.messages.Topic

object McpConstants {
    const val PLUGIN_NAME = "Debugger MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    // Server configuration - IDE-specific defaults
    const val DEFAULT_SERVER_HOST = "127.0.0.1"

    /**
     * Returns the IDE-specific default server port.
     * Each IDE has a unique default port to avoid conflicts when multiple IDEs run simultaneously.
     */
    @JvmStatic
    fun getDefaultServerPort(): Int = IdeProductInfo.getDefaultPort()

    /**
     * Legacy constant for backwards compatibility.
     * New code should use getDefaultServerPort() for IDE-specific ports.
     */
    const val DEFAULT_SERVER_PORT = 29190

    // MCP Endpoint paths (HTTP+SSE transport)
    const val MCP_ENDPOINT_PATH = "/debugger-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"
    const val SESSION_ID_PARAM = "sessionId"

    // JSON-RPC version
    const val JSON_RPC_VERSION = "2.0"

    // MCP Protocol version
    const val MCP_PROTOCOL_VERSION = "2024-11-05"

    // Server identification - IDE-specific
    /**
     * Returns the IDE-specific server name (e.g., "intellij-debugger", "pycharm-debugger").
     */
    @JvmStatic
    fun getServerName(): String = IdeProductInfo.getServerName()

    /**
     * Legacy constant for backwards compatibility.
     */
    const val SERVER_NAME = "jetbrains-debugger"
    const val SERVER_VERSION = "2.0.0"
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

    /**
     * Topic for server status change notifications.
     * Used to notify UI components when the server restarts or encounters errors.
     */
    @JvmField
    val SERVER_STATUS_TOPIC: Topic<ServerStatusListener> = Topic.create(
        "MCP Server Status",
        ServerStatusListener::class.java
    )
}

/**
 * Listener interface for server status changes.
 */
interface ServerStatusListener {
    fun serverStatusChanged()
}
