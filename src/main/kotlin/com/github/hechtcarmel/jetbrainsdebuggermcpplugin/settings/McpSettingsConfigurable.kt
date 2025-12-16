package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.KtorMcpServer
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var maxHistorySpinner: JSpinner? = null
    private var serverPortSpinner: JSpinner? = null

    override fun getDisplayName(): String = McpConstants.SETTINGS_DISPLAY_NAME

    override fun createComponent(): JComponent {
        serverPortSpinner = JSpinner(SpinnerNumberModel(McpConstants.getDefaultServerPort(), 1024, 65535, 1)).apply {
            toolTipText = "The port number for the MCP server (1024-65535). Different IDEs have different defaults to avoid conflicts."
        }
        maxHistorySpinner = JSpinner(SpinnerNumberModel(1000, 100, 10000, 100))

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server port:"), serverPortSpinner!!, 1, false)
            .addLabeledComponent(JBLabel("Max history size:"), maxHistorySpinner!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        return (serverPortSpinner?.value as? Int) != settings.serverPort ||
               (maxHistorySpinner?.value as? Int) != settings.maxHistorySize
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val settings = McpSettings.getInstance()
        val oldPort = settings.serverPort
        val newPort = serverPortSpinner?.value as? Int ?: McpConstants.getDefaultServerPort()

        // Validate port availability before applying (only if port changed)
        if (newPort != oldPort && !isPortAvailable(newPort)) {
            throw ConfigurationException(
                "Port $newPort is already in use. Please choose a different port.",
                "Port Unavailable"
            )
        }

        settings.serverPort = newPort
        settings.maxHistorySize = (maxHistorySpinner?.value as? Int) ?: 1000

        // Auto-restart server if port changed
        if (newPort != oldPort) {
            ApplicationManager.getApplication().invokeLater {
                val result = McpServerService.getInstance().restartServer(newPort)
                when (result) {
                    is KtorMcpServer.StartResult.Success -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                "MCP Server Restarted",
                                "Server is now running on port $newPort",
                                NotificationType.INFORMATION
                            )
                            .notify(null)
                    }
                    is KtorMcpServer.StartResult.PortInUse -> {
                        // This shouldn't happen since we validated above, but handle it anyway
                    }
                    is KtorMcpServer.StartResult.Error -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                "MCP Server Error",
                                result.message,
                                NotificationType.ERROR
                            )
                            .notify(null)
                    }
                }
            }
        }
    }

    /**
     * Checks if a port is available for binding.
     * Returns true if we can bind to the port, false if it's in use.
     */
    private fun isPortAvailable(port: Int): Boolean {
        // If it's the current server port, it's "available" (we'll restart the server)
        val currentPort = McpSettings.getInstance().serverPort
        if (port == currentPort && McpServerService.getInstance().isServerRunning()) {
            return true
        }

        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(McpConstants.DEFAULT_SERVER_HOST, port))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        serverPortSpinner?.value = settings.serverPort
        maxHistorySpinner?.value = settings.maxHistorySize
    }

    override fun disposeUIResources() {
        mainPanel = null
        serverPortSpinner = null
        maxHistorySpinner = null
    }
}
