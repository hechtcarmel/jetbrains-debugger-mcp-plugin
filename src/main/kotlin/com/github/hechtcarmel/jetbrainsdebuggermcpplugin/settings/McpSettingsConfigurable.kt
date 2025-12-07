package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var maxHistorySpinner: JSpinner? = null

    override fun getDisplayName(): String = "Debugger MCP Plugin"

    override fun createComponent(): JComponent {
        maxHistorySpinner = JSpinner(SpinnerNumberModel(1000, 100, 10000, 100))

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Max history size:"), maxHistorySpinner!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        return (maxHistorySpinner?.value as? Int) != settings.maxHistorySize
    }

    override fun apply() {
        val settings = McpSettings.getInstance()
        settings.maxHistorySize = (maxHistorySpinner?.value as? Int) ?: 1000
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        maxHistorySpinner?.value = settings.maxHistorySize
    }

    override fun disposeUIResources() {
        mainPanel = null
        maxHistorySpinner = null
    }
}
