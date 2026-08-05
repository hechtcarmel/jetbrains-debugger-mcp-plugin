package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.icons.McpIcons
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
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
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    private companion object {
        const val REFRESH_ACTION_ID = "DebuggerMcpServer.Refresh"
        const val COPY_SERVER_URL_ACTION_ID = "DebuggerMcpServer.CopyServerUrl"
        const val CLEAR_HISTORY_ACTION_ID = "DebuggerMcpServer.ClearHistory"
        const val EXPORT_HISTORY_ACTION_ID = "DebuggerMcpServer.ExportHistory"
        const val COPY_CLIENT_CONFIG_ACTION_ID = "DebuggerMcpServer.CopyClientConfig"
        const val INSTALL_SKILL_ACTION_ID = "DebuggerMcpServer.InstallSkill"

        /** Actions are registered in plugin.xml; fetching by id keeps a single instance the
         *  keymap, Find Action and this tool window all agree on. */
        fun action(id: String): AnAction =
            checkNotNull(ActionManager.getInstance().getAction(id)) {
                "Action $id is not registered in plugin.xml"
            }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)

        // Left toolbar actions (utility buttons) - settings icon moved to separate component with label
        val leftActionGroup = DefaultActionGroup().apply {
            add(action(REFRESH_ACTION_ID))
            addSeparator()
            add(action(COPY_SERVER_URL_ACTION_ID))
            addSeparator()
            add(action(CLEAR_HISTORY_ACTION_ID))
            add(action(EXPORT_HISTORY_ACTION_ID))
        }

        val leftToolbar = ActionManager.getInstance().createActionToolbar(
            "McpDebuggerToolbarLeft",
            leftActionGroup,
            true
        )
        leftToolbar.targetComponent = panel

        // Settings link with label "Settings"
        val settingsPanel = createSettingsPanel(project)

        // Create prominent "Install on Coding Agents" button with text
        val installAction = action(COPY_CLIENT_CONFIG_ACTION_ID)
        val installButton = JButton("Install on Coding Agents").apply {
            icon = McpIcons.ToolWindow
            toolTipText = "Copy MCP client configuration to clipboard"
            isFocusable = false
            addActionListener { e -> fireToolbarAction(installAction, e.source as Component) }
        }

        val skillAction = action(INSTALL_SKILL_ACTION_ID)
        val skillButton = JButton("Get Companion Skill").apply {
            icon = AllIcons.Actions.Download
            toolTipText = "Install or export the companion skill for AI coding agents"
            isFocusable = false
            addActionListener { e -> fireToolbarAction(skillAction, e.source as Component) }
        }

        // Right panel with external links + install button
        val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(createExternalLink(
                AllIcons.Vcs.Vendors.Github,
                "Star/Report Issues",
                "Star the project or report issues on GitHub",
                "https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin"
            ))
            add(createExternalLink(
                McpIcons.IndexMcp,
                "Try IDE Index MCP Server",
                "Install the companion IDE Index MCP Server plugin",
                "https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server"
            ))
            add(createExternalLink(
                McpIcons.BuyMeACoffee,
                "Buy Me a Coffee",
                "Support the developer on Buy Me a Coffee",
                "https://buymeacoffee.com/hechtcarmel"
            ))
            add(createToolbarSeparator())
            add(skillButton)
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
        // Ties the panel's lifecycle to the content: when the tool window content is removed,
        // the panel's dispose() runs, releasing its message-bus connection and history listener.
        // Without this the panel is never disposed and leaks the Project.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        // Also add quick actions to title bar
        toolWindow.setTitleActions(listOf(action(COPY_SERVER_URL_ACTION_ID), action(REFRESH_ACTION_ID)))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * Fires a registered action from a plain Swing button.
     *
     * `AnAction.actionPerformed` is `@ApiStatus.OverrideOnly` — it must not be invoked directly.
     * `ActionUtil.invokeAction` is the sanctioned entry point: it builds the data context from the
     * source component (so the action still sees the project) and runs the action through the
     * platform's own dispatch. This also avoids the deprecated `AnActionEvent.createFromAnAction`.
     */
    private fun fireToolbarAction(action: AnAction, source: Component) {
        ActionUtil.invokeAction(action, source, ActionPlaces.TOOLWINDOW_CONTENT, null, null)
    }

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

            // Label text - always use HTML to prevent layout shift on hover
            val settingsText = "Settings"
            val settingsLabel = JBLabel("<html>$settingsText</html>").apply {
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
                    settingsLabel.text = "<html><u>$settingsText</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    settingsLabel.text = "<html>$settingsText</html>"
                }
            }

            settingsIcon.addMouseListener(clickHandler)
            settingsLabel.addMouseListener(clickHandler)

            add(settingsIcon)
            add(settingsLabel)
        }
    }

    private fun createToolbarSeparator(): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(JBLabel("|").apply {
                foreground = JBColor.GRAY
            })
        }
    }

    private fun createExternalLink(icon: Icon, text: String, tooltip: String, url: String): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            val linkIcon = JBLabel(icon).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = tooltip
            }

            val linkLabel = JBLabel("<html>$text</html>").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.BLUE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = tooltip
            }

            val clickHandler = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    BrowserUtil.browse(url)
                }
                override fun mouseEntered(e: MouseEvent) {
                    linkLabel.text = "<html><u>$text</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    linkLabel.text = "<html>$text</html>"
                }
            }

            linkIcon.addMouseListener(clickHandler)
            linkLabel.addMouseListener(clickHandler)

            add(linkIcon)
            add(linkLabel)
        }
    }
}
