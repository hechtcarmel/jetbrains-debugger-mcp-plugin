package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object McpIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/debugger-mcp.svg", McpIcons::class.java)

    @JvmField
    val StatusRunning: Icon = IconLoader.getIcon("/icons/status-running.svg", McpIcons::class.java)

    @JvmField
    val StatusStopped: Icon = IconLoader.getIcon("/icons/status-stopped.svg", McpIcons::class.java)

    @JvmField
    val StatusError: Icon = IconLoader.getIcon("/icons/status-error.svg", McpIcons::class.java)
}
