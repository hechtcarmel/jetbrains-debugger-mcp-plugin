package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService

object ClientConfigGenerator {

    enum class ClientType(val displayName: String) {
        CLAUDE_CODE("Claude Code (CLI)"),
        CURSOR("Cursor"),
        VSCODE("VS Code (Generic MCP)"),
        WINDSURF("Windsurf")
    }

    fun generateConfig(clientType: ClientType, serverName: String = "jetbrains-debugger"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> generateClaudeCodeConfig(serverUrl, serverName)
            ClientType.CURSOR -> generateCursorConfig(serverUrl, serverName)
            ClientType.VSCODE -> generateVSCodeConfig(serverUrl, serverName)
            ClientType.WINDSURF -> generateWindsurfConfig(serverUrl, serverName)
        }
    }

    internal fun buildClaudeCodeCommand(serverUrl: String, serverName: String): String {
        val removeCmd = "claude mcp remove $serverName 2>/dev/null"
        val addCmd = "claude mcp add --transport http $serverName $serverUrl --scope user"
        return "$removeCmd ; $addCmd"
    }

    private fun generateClaudeCodeConfig(serverUrl: String, serverName: String): String {
        return buildClaudeCodeCommand(serverUrl, serverName)
    }

    private fun generateCursorConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "url": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    private fun generateVSCodeConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcp.servers": {
    "$serverName": {
      "transport": "sse",
      "url": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    private fun generateWindsurfConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "serverUrl": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    fun getConfigLocationHint(clientType: ClientType): String {
        return when (clientType) {
            ClientType.CLAUDE_CODE -> """
                Runs installation command in your terminal.
                Automatically handles reinstall if already installed (port may change).

                • --scope user: Adds globally for all projects
                • --scope project: Adds to current project only

                To remove manually: claude mcp remove jetbrains-debugger
            """.trimIndent()

            ClientType.CURSOR -> """
                Add to your Cursor MCP configuration:
                • Project-local: .cursor/mcp.json in your project root
                • Global: ~/.cursor/mcp.json
            """.trimIndent()

            ClientType.VSCODE -> """
                Add to your VS Code settings:
                • Open Settings (JSON) and add the configuration
                • Or add to .vscode/settings.json in your project
            """.trimIndent()

            ClientType.WINDSURF -> """
                Add to your Windsurf MCP configuration:
                • Config file: ~/.codeium/windsurf/mcp_config.json
            """.trimIndent()
        }
    }

    fun getAvailableClients(): List<ClientType> = ClientType.entries
}
