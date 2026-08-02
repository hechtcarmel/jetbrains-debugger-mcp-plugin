package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpToolBridge
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
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level service managing the MCP server infrastructure.
 *
 * Owns the [ToolRegistry], the MCP SDK [Server] built from it, and the embedded Ktor edge
 * ([KtorMcpServer]) that exposes the SDK's transports on a configurable host and port.
 *
 * Constructing the service does NOT bind a socket: the tool window may resolve the service on
 * the EDT, and heavy work in a service constructor is forbidden. The server starts when
 * [ensureStarted] is invoked from [com.github.hechtcarmel.jetbrainsdebuggermcpplugin.startup.McpServerStartupActivity].
 *
 * @param coroutineScope platform-injected service scope; the platform cancels it on plugin
 *        unload, so no manual cancellation is needed in [dispose].
 */
@Service(Service.Level.APP)
class McpServerService(@Suppress("unused") private val coroutineScope: CoroutineScope) : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val mcpServer: Server
    private var ktorServer: KtorMcpServer? = null
    private var serverError: ServerError? = null
    private val startRequested = AtomicBoolean(false)

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
        LOG.info("Initializing MCP Server Service")

        // Register built-in tools
        toolRegistry.registerBuiltInTools()
        mcpServer = McpServerFactory.create(toolRegistry, McpToolBridge())

        LOG.info("MCP Server Service initialized (server not yet started)")
    }

    /**
     * Starts the server on the configured host and port, exactly once per application session.
     *
     * Idempotent and thread-safe: with several projects opening concurrently, only the first
     * caller binds the socket. An explicit [restartServer] (settings dialog) is unaffected by
     * this guard.
     */
    fun ensureStarted() {
        if (startRequested.compareAndSet(false, true)) {
            val settings = McpSettings.getInstance()
            startServer(settings.serverPort, settings.serverHost)
        }
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
            mcpServer = mcpServer,
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
        // Close the SDK server too: stopping Ktor drops the sockets, but the SDK's ServerSessions
        // and their coroutines are only released by close(). Leaving them is what turns "plugin
        // can be unloaded without restart" into "restart required".
        //
        // dispose() can run on the EDT (plugin unload / app shutdown), where runBlocking is
        // normally forbidden. It is tolerated here because the wait is hard-bounded at 2s by
        // withTimeoutOrNull and is paid exactly once, at unload. The platform cancels the
        // injected coroutineScope itself, so it is not cancelled here.
        runBlocking { withTimeoutOrNull(2_000) { mcpServer.close() } }
    }
}

