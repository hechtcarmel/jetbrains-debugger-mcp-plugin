package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.toSdkTool
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Builds the MCP [Server] that backs every transport.
 *
 * One `Server` serves all three transports; each connection becomes a `ServerSession` under it, so
 * tool registration happens exactly once.
 */
object McpServerFactory {

    fun create(registry: ToolRegistry, bridge: McpToolBridge): Server {
        val server = Server(
            serverInfo = Implementation(
                name = McpConstants.getServerName(),
                version = McpConstants.SERVER_VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            ),
            // The server's prose description belongs in `instructions`. The pre-SDK code hung it off
            // a non-standard `serverInfo.description` field, which the spec has no slot for and
            // which would have vanished silently here, with no compile error.
            instructions = McpConstants.SERVER_DESCRIPTION,
        )

        // Sorted so `tools/list` is deterministic. ToolRegistry is a ConcurrentHashMap, whose
        // iteration order is arbitrary and was previously observable by clients.
        registry.getAllTools().sortedBy { it.name }.forEach { tool ->
            val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult =
                { _, request -> bridge.invoke(tool, request) }
            server.addTool(tool.toSdkTool(), handler)
        }

        return server
    }
}
