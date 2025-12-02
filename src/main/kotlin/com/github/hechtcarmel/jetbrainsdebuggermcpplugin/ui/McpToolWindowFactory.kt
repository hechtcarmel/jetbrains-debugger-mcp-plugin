package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.ClearHistoryAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.CopyClientConfigAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.CopyServerUrlAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.ExportHistoryAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.RefreshAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.icons.McpIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)

        val leftActionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(CopyServerUrlAction())
            addSeparator()
            add(ClearHistoryAction())
            add(ExportHistoryAction())
        }

        val leftToolbar = ActionManager.getInstance().createActionToolbar(
            "McpDebuggerToolbarLeft",
            leftActionGroup,
            true
        )
        leftToolbar.targetComponent = panel

        val installAction = CopyClientConfigAction()
        val installButton = JButton("Install on Coding Agents").apply {
            icon = McpIcons.ToolWindow
            toolTipText = "Copy MCP client configuration to clipboard"
            isFocusable = false
            addActionListener {
                val dataContext = com.intellij.openapi.actionSystem.DataContext { dataId ->
                    when (dataId) {
                        com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
                        else -> null
                    }
                }
                val event = AnActionEvent.createFromAnAction(
                    installAction,
                    null,
                    ActionPlaces.TOOLWINDOW_CONTENT,
                    dataContext
                )
                installAction.actionPerformed(event)
            }
        }

        val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(installButton)
        }

        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(leftToolbar.component, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        val wrapperPanel = JPanel(BorderLayout()).apply {
            add(toolbarPanel, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(
            wrapperPanel,
            McpConstants.PLUGIN_NAME,
            false
        )
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(listOf(CopyServerUrlAction(), RefreshAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
