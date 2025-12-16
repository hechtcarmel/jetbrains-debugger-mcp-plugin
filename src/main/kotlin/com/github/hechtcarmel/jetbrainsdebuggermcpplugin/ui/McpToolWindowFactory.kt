package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.ClearHistoryAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.CopyClientConfigAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.CopyServerUrlAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.ExportHistoryAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.RefreshAction
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.icons.McpIcons
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel

class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)

        // Left toolbar actions (utility buttons) - settings icon moved to separate component with label
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

        // Settings link with label "Change port"
        val settingsPanel = createSettingsPanel(project)

        // Create prominent "Install on Coding Agents" button with text
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

        // Right panel with the button
        val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(installButton)
        }

        // Left panel: toolbar + settings link inline
        val leftPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(leftToolbar.component)
            add(settingsPanel)
        }

        // Create toolbar panel with left actions + settings on left, install button on right
        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        // Create wrapper panel with toolbar at top and main panel in center
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

        // Also add quick actions to title bar
        toolWindow.setTitleActions(listOf(CopyServerUrlAction(), RefreshAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * Creates a settings panel with an icon and descriptive text.
     */
    private fun createSettingsPanel(project: Project): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 8, 2, 0)

            // Settings icon
            val settingsIcon = JBLabel(AllIcons.General.Settings).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open MCP Server settings"
            }

            // Label text
            val settingsLabel = JBLabel("Change port").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.BLUE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open MCP Server settings"
            }

            // Click handler for both icon and label
            val clickHandler = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSettingsConfigurable::class.java)
                }
                override fun mouseEntered(e: MouseEvent) {
                    settingsLabel.text = "<html><u>Change port</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    settingsLabel.text = "Change port"
                }
            }

            settingsIcon.addMouseListener(clickHandler)
            settingsLabel.addMouseListener(clickHandler)

            add(settingsIcon)
            add(settingsLabel)
        }
    }
}
