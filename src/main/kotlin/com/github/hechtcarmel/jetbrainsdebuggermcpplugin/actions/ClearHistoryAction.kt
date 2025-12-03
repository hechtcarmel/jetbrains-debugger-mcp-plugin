package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class ClearHistoryAction : AnAction(
    "Clear History",
    "Clear command history",
    AllIcons.Actions.GC
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to clear the command history?",
            "Clear History",
            Messages.getQuestionIcon()
        )

        if (result == Messages.YES) {
            CommandHistoryService.getInstance(project).clearHistory()

            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Command history cleared",
                    NotificationType.INFORMATION
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
