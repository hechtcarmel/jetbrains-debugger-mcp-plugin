package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ServerStatusListener
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandFilter
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryListener
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

class McpToolWindowPanel(
    private val project: Project
) : JBPanel<McpToolWindowPanel>(BorderLayout()), Disposable, CommandHistoryListener, ServerStatusListener {

    private val serverStatusPanel: ServerStatusPanel
    private val filterToolbar: FilterToolbar
    private val historyListModel = DefaultListModel<CommandEntry>()
    private val historyList: JBList<CommandEntry>
    private val detailsArea: JBTextArea
    private val historyService: CommandHistoryService
    private var currentFilter = CommandFilter()
    private val messageBusConnection: MessageBusConnection

    init {
        // Subscribe to server status changes
        messageBusConnection = ApplicationManager.getApplication().messageBus.connect(this)
        messageBusConnection.subscribe(McpConstants.SERVER_STATUS_TOPIC, this)

        val headerPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        serverStatusPanel = ServerStatusPanel(project)
        headerPanel.add(serverStatusPanel)

        val agentRuleTipPanel = AgentRuleTipPanel(project)
        headerPanel.add(agentRuleTipPanel)

        filterToolbar = FilterToolbar { filter ->
            currentFilter = filter
            refreshHistory()
        }
        headerPanel.add(filterToolbar)

        add(headerPanel, BorderLayout.NORTH)

        historyList = JBList(historyListModel).apply {
            cellRenderer = CommandListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedValue?.let { showCommandDetails(it) }
                }
            }
        }

        detailsArea = JBTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }

        val splitter = JBSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(historyList)
            secondComponent = JBScrollPane(detailsArea)
        }

        add(splitter, BorderLayout.CENTER)

        historyService = CommandHistoryService.getInstance(project)
        historyService.addListener(this)
        refreshHistory()
    }

    fun refresh() {
        serverStatusPanel.refresh()
        refreshHistory()
    }

    private fun refreshHistory() {
        historyListModel.clear()
        val entries = if (currentFilter.isEmpty()) {
            historyService.entries
        } else {
            historyService.getFilteredHistory(currentFilter)
        }
        entries.forEach { historyListModel.addElement(it) }
    }

    private fun showCommandDetails(entry: CommandEntry) {
        val sb = StringBuilder()
        sb.appendLine("Tool: ${entry.toolName}")
        sb.appendLine("Status: ${entry.status}")
        sb.appendLine("Timestamp: ${entry.timestamp}")
        entry.durationMs?.let { sb.appendLine("Duration: ${it}ms") }
        sb.appendLine()
        sb.appendLine("Parameters:")
        sb.appendLine(entry.parameters.toString())
        sb.appendLine()

        if (entry.error != null) {
            sb.appendLine("Error:")
            sb.appendLine(entry.error)
            sb.appendLine()
        }

        if (entry.result != null) {
            sb.appendLine("Result:")
            sb.appendLine(entry.result)
        }

        if (entry.error == null && entry.result == null && entry.status == CommandStatus.ERROR) {
            sb.appendLine("Error: (no details available)")
        }

        entry.affectedFiles?.let { files ->
            if (files.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Affected Files:")
                files.forEach { sb.appendLine("  - $it") }
            }
        }

        detailsArea.text = sb.toString()
        detailsArea.caretPosition = 0
    }

    override fun onCommandAdded(entry: CommandEntry) {
        historyListModel.add(0, entry)
        historyList.selectedIndex = 0
    }

    override fun onCommandUpdated(entry: CommandEntry) {
        val index = (0 until historyListModel.size).firstOrNull {
            historyListModel.getElementAt(it).id == entry.id
        }
        index?.let {
            historyListModel.setElementAt(entry, it)
            if (historyList.selectedIndex == it) {
                showCommandDetails(entry)
            }
        }
    }

    override fun onHistoryCleared() {
        historyListModel.clear()
        detailsArea.text = ""
    }

    override fun serverStatusChanged() {
        // Refresh UI when server status changes (e.g., after port change)
        serverStatusPanel.refresh()
    }

    override fun dispose() {
        historyService.removeListener(this)
    }
}

class ServerStatusPanel(private val project: Project) : JBPanel<ServerStatusPanel>(BorderLayout()) {

    private val statusLabel: JBLabel
    private val urlLabel: JBLabel
    private val projectLabel: JBLabel
    private val settingsLink: JBLabel

    init {
        border = JBUI.Borders.empty(8)

        val leftPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))

        statusLabel = JBLabel().apply {
            icon = null
            font = font.deriveFont(Font.BOLD)
        }

        urlLabel = JBLabel().apply {
            foreground = JBColor.BLUE
        }

        projectLabel = JBLabel()

        settingsLink = JBLabel("Open Settings").apply {
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isVisible = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, McpSettingsConfigurable::class.java)
                }
                override fun mouseEntered(e: MouseEvent) {
                    text = "<html><u>Open Settings</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    text = "Open Settings"
                }
            })
        }

        leftPanel.add(statusLabel)
        leftPanel.add(urlLabel)
        leftPanel.add(settingsLink)
        leftPanel.add(projectLabel)

        add(leftPanel, BorderLayout.WEST)

        refresh()
    }

    fun refresh() {
        try {
            val mcpService = McpServerService.getInstance()
            val error = mcpService.getServerError()

            if (error != null) {
                // Error state - show error message with settings link
                statusLabel.text = "MCP Server Error"
                statusLabel.foreground = JBColor.RED
                urlLabel.text = error.message
                urlLabel.foreground = JBColor.RED
                settingsLink.isVisible = true
                projectLabel.text = ""
            } else if (mcpService.isServerRunning()) {
                // Running state
                val url = mcpService.getServerUrl()
                statusLabel.text = "MCP Server Running"
                statusLabel.foreground = JBColor(0x59A869, 0x59A869)
                urlLabel.text = url ?: ""
                urlLabel.foreground = JBColor.BLUE
                settingsLink.isVisible = false
                projectLabel.text = "| Project: ${project.name}"
            } else {
                // Stopped state
                statusLabel.text = "MCP Server Stopped"
                statusLabel.foreground = JBColor.GRAY
                urlLabel.text = ""
                settingsLink.isVisible = true
                projectLabel.text = ""
            }
        } catch (e: Exception) {
            statusLabel.text = "MCP Server Error"
            statusLabel.foreground = JBColor.RED
            urlLabel.text = e.message ?: ""
            urlLabel.foreground = JBColor.RED
            settingsLink.isVisible = true
            projectLabel.text = ""
        }
    }
}

class CommandListCellRenderer : ListCellRenderer<CommandEntry> {

    private val panel = JPanel(BorderLayout())
    private val timestampLabel = JBLabel()
    private val toolNameLabel = JBLabel()
    private val statusLabel = JBLabel()

    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        panel.border = JBUI.Borders.empty(4, 8)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        leftPanel.isOpaque = false
        leftPanel.add(timestampLabel)
        leftPanel.add(toolNameLabel)

        panel.add(leftPanel, BorderLayout.WEST)
        panel.add(statusLabel, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out CommandEntry>,
        value: CommandEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        timestampLabel.text = value.timestamp.atZone(ZoneId.systemDefault()).format(formatter)
        timestampLabel.foreground = JBColor.GRAY

        toolNameLabel.text = value.toolName
        toolNameLabel.font = toolNameLabel.font.deriveFont(Font.BOLD)

        statusLabel.text = value.status.name
        statusLabel.foreground = when (value.status) {
            CommandStatus.SUCCESS -> JBColor(0x59A869, 0x59A869)
            CommandStatus.ERROR -> JBColor(0xE05555, 0xE05555)
            CommandStatus.PENDING -> JBColor(0xD9A343, 0xD9A343)
        }

        panel.background = if (isSelected) {
            list.selectionBackground
        } else {
            list.background
        }

        toolNameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        return panel
    }
}

class FilterToolbar(
    private val onFilterChanged: (CommandFilter) -> Unit
) : JBPanel<FilterToolbar>(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val toolNameComboBox: JComboBox<String>
    private val statusComboBox: JComboBox<String>
    private val searchField: SearchTextField

    init {
        border = JBUI.Borders.empty(4, 8)

        add(JBLabel("Tool:"))
        toolNameComboBox = JComboBox(getToolNames()).apply {
            addActionListener { notifyFilterChanged() }
        }
        add(toolNameComboBox)

        add(JBLabel("Status:"))
        statusComboBox = JComboBox(arrayOf("All", "SUCCESS", "ERROR", "PENDING")).apply {
            addActionListener { notifyFilterChanged() }
        }
        add(statusComboBox)

        add(JBLabel("Search:"))
        searchField = SearchTextField().apply {
            textEditor.columns = 15
            addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    notifyFilterChanged()
                }
            })
        }
        add(searchField)
    }

    private fun getToolNames(): Array<String> {
        val toolNames = McpServerService.getInstance().getToolRegistry().getAllTools().map { it.name }
        return (listOf("All") + toolNames).toTypedArray()
    }

    private fun notifyFilterChanged() {
        val toolName = toolNameComboBox.selectedItem as? String
        val status = statusComboBox.selectedItem as? String
        val searchText = searchField.text.takeIf { it.isNotBlank() }

        val filter = CommandFilter(
            toolName = if (toolName == "All") null else toolName,
            status = if (status == "All") null else CommandStatus.valueOf(status!!),
            searchText = searchText
        )
        onFilterChanged(filter)
    }
}

class AgentRuleTipPanel(private val project: Project) : JBPanel<AgentRuleTipPanel>(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    companion object {
        val CONFIG_FILES_HINT = """
            Add this rule to your AI agent's configuration file:
            - Claude Code: CLAUDE.md (project root) or ~/.claude/CLAUDE.md (global)
            - Cursor: .cursorrules or .cursor/rules/*.mdc
            - Other agents: Check your agent's documentation
        """.trimIndent()
    }

    init {
        border = JBUI.Borders.empty(2, 8)
        background = JBColor(0xFFFBE6, 0x3D3D00)

        val iconLabel = JBLabel(AllIcons.General.BalloonInformation)

        val tipLabel = JBLabel("Add jetbrains-debugger MCP rule to your AI agent config").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        val copyLink = JBLabel("Copy rule").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    copyAgentRule()
                }

                override fun mouseEntered(e: MouseEvent) {
                    text = "<html><u>Copy rule</u></html>"
                }

                override fun mouseExited(e: MouseEvent) {
                    text = "Copy rule"
                }
            })
        }

        add(iconLabel)
        add(tipLabel)
        add(copyLink)
    }

    private fun copyAgentRule() {
        CopyPasteManager.getInstance().setContents(StringSelection(McpConstants.AGENT_RULE_TEXT))

        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(
                "Agent Rule Copied",
                "${McpConstants.AGENT_RULE_TEXT}\n\n$CONFIG_FILES_HINT",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
