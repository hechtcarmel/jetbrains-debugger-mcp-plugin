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
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var serverHostComboBox: JComboBox<String>? = null
    private var maxHistorySpinner: JSpinner? = null
    private var serverPortSpinner: JSpinner? = null

    override fun getDisplayName(): String = McpConstants.SETTINGS_DISPLAY_NAME

    override fun createComponent(): JComponent {
        serverHostComboBox = JComboBox<String>(arrayOf("127.0.0.1", "0.0.0.0")).apply {
            isEditable = true
            toolTipText = "The bind address for the MCP server. Use 127.0.0.1 for localhost only, 0.0.0.0 for all interfaces, or enter a custom IP."
        }
        serverPortSpinner = JSpinner(SpinnerNumberModel(McpConstants.getDefaultServerPort(), 1024, 65535, 1)).apply {
            toolTipText = "The port number for the MCP server (1024-65535). Different IDEs have different defaults to avoid conflicts."
        }
        maxHistorySpinner = JSpinner(SpinnerNumberModel(1000, 100, 10000, 100))

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server host:"), serverHostComboBox!!, 1, false)
            .addLabeledComponent(JBLabel("Server port:"), serverPortSpinner!!, 1, false)
            .addLabeledComponent(JBLabel("Max history size:"), maxHistorySpinner!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        return (serverHostComboBox?.selectedItem as? String ?: McpConstants.DEFAULT_SERVER_HOST) != settings.serverHost ||
               (serverPortSpinner?.value as? Int) != settings.serverPort ||
               (maxHistorySpinner?.value as? Int) != settings.maxHistorySize
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val settings = McpSettings.getInstance()
        val oldPort = settings.serverPort
        val oldHost = settings.serverHost
        val newPort = serverPortSpinner?.value as? Int ?: McpConstants.getDefaultServerPort()
        val newHost = (serverHostComboBox?.selectedItem as? String)?.trim() ?: McpConstants.DEFAULT_SERVER_HOST

        // Validate port availability before applying (only if port or host changed)
        if ((newPort != oldPort || newHost != oldHost) && !isPortAvailable(newPort, newHost)) {
            throw ConfigurationException(
                "Port $newPort is already in use on $newHost. Please choose a different port or host.",
                "Port Unavailable"
            )
        }

        settings.serverPort = newPort
        settings.serverHost = newHost
        settings.maxHistorySize = (maxHistorySpinner?.value as? Int) ?: 1000

        // Auto-restart server if port or host changed
        if (newPort != oldPort || newHost != oldHost) {
            ApplicationManager.getApplication().invokeLater {
                val result = McpServerService.getInstance().restartServer(newPort, newHost)
                when (result) {
                    is KtorMcpServer.StartResult.Success -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                "MCP Server Restarted",
                                "Server is now running on $newHost:$newPort",
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
    private fun isPortAvailable(port: Int, host: String = McpSettings.getInstance().serverHost): Boolean {
        val settings = McpSettings.getInstance()
        val currentPort = settings.serverPort
        val currentHost = settings.serverHost
        if (port == currentPort && host == currentHost && McpServerService.getInstance().isServerRunning()) {
            return true
        }

        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        serverHostComboBox?.selectedItem = settings.serverHost
        serverPortSpinner?.value = settings.serverPort
        maxHistorySpinner?.value = settings.maxHistorySize
    }

    override fun disposeUIResources() {
        mainPanel = null
        serverHostComboBox = null
        serverPortSpinner = null
        maxHistorySpinner = null
    }
}
