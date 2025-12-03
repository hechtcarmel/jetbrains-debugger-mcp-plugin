package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService

object ClientConfigGenerator {

    enum class ClientType(val displayName: String, val supportsInstallCommand: Boolean = false) {
        CLAUDE_CODE("Claude Code", true),
        GEMINI_CLI("Gemini CLI"),
        CURSOR("Cursor")
    }

    fun generateConfig(clientType: ClientType, serverName: String = "jetbrains-debugger"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> generateClaudeCodeConfig(serverUrl, serverName)
            ClientType.GEMINI_CLI -> generateGeminiCliConfig(serverUrl, serverName)
            ClientType.CURSOR -> generateCursorConfig(serverUrl, serverName)
        }
    }

    fun generateInstallCommand(clientType: ClientType, serverName: String = "jetbrains-debugger"): String? {
        if (!clientType.supportsInstallCommand) return null
        val serverUrl = McpServerService.getInstance().getServerUrl()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> buildClaudeCodeCommand(serverUrl, serverName)
            else -> null
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

    private fun generateGeminiCliConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "$serverUrl",
        "--allow-http"
      ]
    }
  }
}
        """.trimIndent()
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

    fun generateStandardSseConfig(serverName: String = "jetbrains-debugger"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()
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

    fun generateMcpRemoteConfig(serverName: String = "jetbrains-debugger"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()
        return """
{
  "mcpServers": {
    "$serverName": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "$serverUrl",
        "--allow-http"
      ]
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

            ClientType.GEMINI_CLI -> """
                Add to your Gemini CLI settings file:
                • Config file: ~/.gemini/settings.json

                Uses mcp-remote to bridge SSE to stdio transport.
                Requires Node.js/npx to be available in your PATH.
            """.trimIndent()

            ClientType.CURSOR -> """
                Add to your Cursor MCP configuration:
                • Project-local: .cursor/mcp.json in your project root
                • Global: ~/.cursor/mcp.json
            """.trimIndent()
        }
    }

    fun getStandardSseHint(): String = """
        Standard MCP configuration using SSE (Server-Sent Events) transport.
        Use this for any MCP client that supports the SSE transport natively.
    """.trimIndent()

    fun getMcpRemoteHint(): String = """
        For MCP clients that don't support SSE transport natively.
        Uses mcp-remote to bridge SSE to stdio transport.

        Requires Node.js and npx to be available in your PATH.
        The --allow-http flag is needed for localhost connections.
    """.trimIndent()

    fun getAvailableClients(): List<ClientType> = ClientType.entries

    fun getInstallableClients(): List<ClientType> = ClientType.entries.filter { it.supportsInstallCommand }

    fun getCopyableClients(): List<ClientType> = ClientType.entries
}
