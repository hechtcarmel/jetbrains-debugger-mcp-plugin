package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui.McpToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container

class RefreshAction : AnAction(
    "Refresh",
    "Refresh server status and history",
    AllIcons.Actions.Refresh
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(McpConstants.TOOL_WINDOW_ID)
        toolWindow?.contentManager?.contents?.forEach { content ->
            findPanel(content.component)?.refresh()
        }
    }

    /**
     * The registered content is a wrapper `JPanel` holding the toolbar and the real panel
     * (McpToolWindowFactory.createToolWindowContent), so the panel is never the content component
     * itself — testing `content.component is McpToolWindowPanel` made this action a silent no-op.
     */
    private fun findPanel(component: Component): McpToolWindowPanel? = when {
        component is McpToolWindowPanel -> component
        component is Container -> component.components.firstNotNullOfOrNull { findPanel(it) }
        else -> null
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
