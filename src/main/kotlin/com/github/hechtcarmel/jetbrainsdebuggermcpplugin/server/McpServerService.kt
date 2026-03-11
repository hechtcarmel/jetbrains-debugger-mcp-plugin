package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettingsConfigurable
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application-level service managing the MCP server infrastructure.
 *
 * This service manages:
 * - Embedded Ktor CIO server with configurable port
 * - Tool registry for MCP tools
 * - JSON-RPC handler for message processing
 * - SSE session management for client connections
 * - Coroutine scope for non-blocking tool execution
 *
 * Uses HTTP+SSE transport for compatibility with MCP clients.
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val jsonRpcHandler: JsonRpcHandler
    private val sseSessionManager: KtorSseSessionManager = KtorSseSessionManager()
    private val streamableHttpSessionManager: StreamableHttpSessionManager = StreamableHttpSessionManager()
    private var ktorServer: KtorMcpServer? = null
    private var serverError: ServerError? = null

    /**
     * Coroutine scope for non-blocking tool execution.
     * Uses SupervisorJob so failures in one tool don't cancel others.
     * Uses Default dispatcher for CPU-bound operations.
     */
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Represents a server error state.
     */
    data class ServerError(
        val message: String,
        val port: Int? = null
    )

    companion object {
        private val LOG = logger<McpServerService>()

        fun getInstance(): McpServerService = service()
    }

    init {
        LOG.info("Initializing MCP Server Service (Protocol: ${McpConstants.MCP_PROTOCOL_VERSION})")
        jsonRpcHandler = JsonRpcHandler(toolRegistry)

        // Register built-in tools
        toolRegistry.registerBuiltInTools()

        // Start the Ktor server with configured port and host
        val settings = McpSettings.getInstance()
        startServer(settings.serverPort, settings.serverHost)

        LOG.info("MCP Server Service initialized with Ktor CIO server")
    }

    /**
     * Starts the MCP server on the specified port.
     *
     * @param port The port to listen on
     * @return The result of the start operation
     */
    fun startServer(port: Int, host: String = McpSettings.getInstance().serverHost): KtorMcpServer.StartResult {
        // Stop existing server if running
        stopServer()

        LOG.info("Starting MCP Server on $host:$port")

        val server = KtorMcpServer(
            port = port,
            host = host,
            jsonRpcHandler = jsonRpcHandler,
            sseSessionManager = sseSessionManager,
            streamableHttpSessionManager = streamableHttpSessionManager,
            coroutineScope = coroutineScope
        )

        val result = when (val startResult = server.start()) {
            is KtorMcpServer.StartResult.Success -> {
                ktorServer = server
                serverError = null
                LOG.info("MCP Server started successfully on port $port")
                startResult
            }
            is KtorMcpServer.StartResult.PortInUse -> {
                serverError = ServerError("Port $port is already in use", port)
                showPortInUseNotification(port)
                startResult
            }
            is KtorMcpServer.StartResult.Error -> {
                serverError = ServerError(startResult.message)
                LOG.error("Failed to start MCP Server: ${startResult.message}")
                startResult
            }
        }

        // Notify listeners that server status changed
        notifyStatusChanged()

        return result
    }

    /**
     * Notifies all listeners that the server status has changed.
     */
    private fun notifyStatusChanged() {
        ApplicationManager.getApplication().invokeLater({
            ApplicationManager.getApplication().messageBus
                .syncPublisher(McpConstants.SERVER_STATUS_TOPIC)
                .serverStatusChanged()
        }, com.intellij.openapi.application.ModalityState.any())
    }

    /**
     * Stops the MCP server.
     */
    fun stopServer() {
        ktorServer?.stop()
        ktorServer = null
    }

    /**
     * Restarts the MCP server on a new port.
     *
     * @param newPort The new port to listen on
     * @return The result of the restart operation
     */
    fun restartServer(newPort: Int, newHost: String = McpSettings.getInstance().serverHost): KtorMcpServer.StartResult {
        LOG.info("Restarting MCP Server on $newHost:$newPort")
        return startServer(newPort, newHost)
    }

    /**
     * Returns whether the server is currently running.
     */
    fun isServerRunning(): Boolean = ktorServer?.isRunning() == true

    /**
     * Returns the current server error, if any.
     */
    fun getServerError(): ServerError? = serverError

    fun getToolRegistry(): ToolRegistry = toolRegistry

    fun getJsonRpcHandler(): JsonRpcHandler = jsonRpcHandler

    fun getSseSessionManager(): KtorSseSessionManager = sseSessionManager

    /**
     * Returns the SSE endpoint URL for MCP connections.
     * Clients should connect to this URL to establish SSE stream.
     *
     * @return The server URL, or null if server is not running
     */
    /**
     * Returns the Streamable HTTP endpoint URL for MCP connections (primary transport).
     * Clients should use this URL for the MCP 2025-03-26 Streamable HTTP transport.
     *
     * @return The server URL, or null if server is not running
     */
    fun getServerUrl(): String? {
        if (ktorServer == null || serverError != null) return null
        val settings = McpSettings.getInstance()
        return "http://${settings.serverHost}:${settings.serverPort}${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
    }

    /**
     * Returns the legacy SSE endpoint URL for older MCP clients (2024-11-05 transport).
     *
     * @return The SSE URL, or null if server is not running
     */
    fun getLegacySseUrl(): String? {
        if (ktorServer == null || serverError != null) return null
        val settings = McpSettings.getInstance()
        return "http://${settings.serverHost}:${settings.serverPort}${McpConstants.SSE_ENDPOINT_PATH}"
    }

    /**
     * Returns the configured server port.
     */
    fun getServerPort(): Int = McpSettings.getInstance().serverPort


    /**
     * Shows a notification when the port is already in use.
     */
    private fun showPortInUseNotification(port: Int) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    "MCP Server Error",
                    "Port $port is already in use. Please choose a different port in Settings.",
                    NotificationType.ERROR
                )
                .addAction(object : NotificationAction("Open Settings") {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(null, McpSettingsConfigurable::class.java)
                        notification.expire()
                    }
                })
                .notify(null)
        }
    }

    override fun dispose() {
        LOG.info("Disposing MCP Server Service")
        stopServer()
        sseSessionManager.closeAllSessions()
        streamableHttpSessionManager.closeAllSessions()
        coroutineScope.cancel("McpServerService disposed")
    }
}

/**
 * Data class containing server status information.
 */
data class ServerStatusInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val streamableHttpUrl: String,
    val legacySseUrl: String,
    val postUrl: String,
    val port: Int,
    val registeredTools: Int,
    val error: String? = null,
    val isRunning: Boolean = true
)
