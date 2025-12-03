package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.vfs.VirtualFile

class ExportHistoryAction : AnAction(
    "Export History",
    "Export command history to file",
    AllIcons.ToolbarDecorator.Export
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val historyService = CommandHistoryService.getInstance(project)

        val descriptor = FileSaverDescriptor(
            "Export Command History",
            "Save command history to file",
            "json", "csv"
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, "debugger-mcp-history")

        wrapper?.let { vfw ->
            val file = vfw.file
            val extension = file.extension.lowercase()

            val content = when (extension) {
                "csv" -> historyService.exportToCsv()
                else -> historyService.exportToJson()
            }

            file.writeText(content)

            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    "History exported to ${file.path}",
                    NotificationType.INFORMATION
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
