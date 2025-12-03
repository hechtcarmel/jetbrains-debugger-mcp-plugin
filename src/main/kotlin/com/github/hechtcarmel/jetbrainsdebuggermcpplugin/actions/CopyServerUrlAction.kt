package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class CopyServerUrlAction : AnAction(
    "Copy URL",
    "Copy the MCP server URL to clipboard",
    AllIcons.Actions.Copy
) {
    override fun actionPerformed(e: AnActionEvent) {
        val url = McpServerService.getInstance().getServerUrl()
        CopyPasteManager.getInstance().setContents(StringSelection(url))

        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(
                "Server URL copied to clipboard",
                NotificationType.INFORMATION
            )
            .notify(e.project)
    }
}
