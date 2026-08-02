package com.github.hechtcarmel.jetbrainsdebuggermcpplugin

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.IdeProductInfo
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.messages.Topic

object McpConstants {
    const val PLUGIN_NAME = "Debugger MCP Server"
    const val PLUGIN_ID = "com.github.hechtcarmel.jetbrainsdebuggermcpplugin"
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


    // MCP Endpoint paths
    const val MCP_ENDPOINT_PATH = "/debugger-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"
    const val STREAMABLE_HTTP_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/streamable-http"
    const val SESSION_ID_PARAM = "sessionId"
    const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"



    // Server identification - IDE-specific
    /**
     * Returns the IDE-specific server name (e.g., "intellij-debugger", "pycharm-debugger").
     */
    @JvmStatic
    fun getServerName(): String = IdeProductInfo.getServerName()

    /**
     * Fallback for [SERVER_VERSION] used when the plugin descriptor is not loaded (plain unit
     * tests). Must match `pluginVersion` in gradle.properties — `McpConstantsTest` pins that.
     */
    const val FALLBACK_SERVER_VERSION = "5.0.0"

    /**
     * The version reported to MCP clients during `initialize`.
     *
     * Read from the installed plugin descriptor at runtime, so it cannot drift from the version
     * the plugin actually ships — the previous hardcoded constant drifted twice, once for four
     * consecutive releases. The fallback only applies where the plugin is not loaded as a plugin
     * (unit tests).
     */
    @JvmStatic
    val SERVER_VERSION: String by lazy {
        runCatching { PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version }
            .getOrNull() ?: FALLBACK_SERVER_VERSION
    }
    const val SERVER_DESCRIPTION = """Debug applications running in JetBrains IDEs (IntelliJ, PyCharm, WebStorm, etc.) through programmatic control.

When to use: Use this server when you need to:
- Set breakpoints and step through code to understand execution flow
- Inspect and modify variable values during debugging
- Evaluate expressions in the context of paused execution
- Navigate stack traces and threads to diagnose issues

Requirements: An open JetBrains IDE with a debuggable project. The IDE must be running with this plugin installed.

Typical workflow: list_run_configurations -> start_debug_session -> set_breakpoint -> resume_execution -> get_debug_session_status (when paused) -> get_variables / evaluate_expression"""

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
