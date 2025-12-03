package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui.McpToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class RefreshAction : AnAction(
    "Refresh",
    "Refresh server status and history",
    AllIcons.Actions.Refresh
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(McpConstants.TOOL_WINDOW_ID)
        toolWindow?.contentManager?.contents?.forEach { content ->
            val component = content.component
            if (component is McpToolWindowPanel) {
                component.refresh()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
