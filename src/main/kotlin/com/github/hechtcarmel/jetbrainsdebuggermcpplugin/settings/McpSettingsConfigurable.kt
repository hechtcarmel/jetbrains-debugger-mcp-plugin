package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var portField: JBTextField? = null
    private var maxHistorySpinner: JSpinner? = null
    private var autoScrollCheckBox: JBCheckBox? = null
    private var showNotificationsCheckBox: JBCheckBox? = null
    private var enableAutoStartCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "Debugger MCP Plugin"

    override fun createComponent(): JComponent {
        portField = JBTextField().apply {
            text = "0"
            toolTipText = "Server port (0 = auto-assign)"
        }

        maxHistorySpinner = JSpinner(SpinnerNumberModel(1000, 100, 10000, 100))
        autoScrollCheckBox = JBCheckBox("Auto-scroll to latest commands")
        showNotificationsCheckBox = JBCheckBox("Show notifications")
        enableAutoStartCheckBox = JBCheckBox("Auto-start server on IDE launch")

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server port (0 = auto):"), portField!!, 1, false)
            .addLabeledComponent(JBLabel("Max history size:"), maxHistorySpinner!!, 1, false)
            .addComponent(autoScrollCheckBox!!, 1)
            .addComponent(showNotificationsCheckBox!!, 1)
            .addComponent(enableAutoStartCheckBox!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        return portField?.text?.toIntOrNull() != settings.serverPort ||
               (maxHistorySpinner?.value as? Int) != settings.maxHistorySize ||
               autoScrollCheckBox?.isSelected != settings.autoScroll ||
               showNotificationsCheckBox?.isSelected != settings.showNotifications ||
               enableAutoStartCheckBox?.isSelected != settings.enableAutoStart
    }

    override fun apply() {
        val settings = McpSettings.getInstance()
        settings.serverPort = portField?.text?.toIntOrNull() ?: 0
        settings.maxHistorySize = (maxHistorySpinner?.value as? Int) ?: 1000
        settings.autoScroll = autoScrollCheckBox?.isSelected ?: true
        settings.showNotifications = showNotificationsCheckBox?.isSelected ?: true
        settings.enableAutoStart = enableAutoStartCheckBox?.isSelected ?: false
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        portField?.text = settings.serverPort.toString()
        maxHistorySpinner?.value = settings.maxHistorySize
        autoScrollCheckBox?.isSelected = settings.autoScroll
        showNotificationsCheckBox?.isSelected = settings.showNotifications
        enableAutoStartCheckBox?.isSelected = settings.enableAutoStart
    }

    override fun disposeUIResources() {
        mainPanel = null
        portField = null
        maxHistorySpinner = null
        autoScrollCheckBox = null
        showNotificationsCheckBox = null
        enableAutoStartCheckBox = null
    }
}
