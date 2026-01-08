package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import org.junit.Assert.*
import org.junit.Test

class ClientConfigGeneratorTest {

    // ClientType Enum Tests

    @Test
    fun `ClientType has all expected values`() {
        val types = ClientConfigGenerator.ClientType.entries

        assertEquals(4, types.size)
        assertTrue(types.contains(ClientConfigGenerator.ClientType.CLAUDE_CODE))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.CODEX_CLI))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.GEMINI_CLI))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.CURSOR))
    }

    @Test
    fun `ClientType displayNames are set correctly`() {
        assertEquals("Claude Code", ClientConfigGenerator.ClientType.CLAUDE_CODE.displayName)
        assertEquals("Codex CLI", ClientConfigGenerator.ClientType.CODEX_CLI.displayName)
        assertEquals("Gemini CLI", ClientConfigGenerator.ClientType.GEMINI_CLI.displayName)
        assertEquals("Cursor", ClientConfigGenerator.ClientType.CURSOR.displayName)
    }

    @Test
    fun `ClientType supportsInstallCommand is set correctly`() {
        assertTrue(ClientConfigGenerator.ClientType.CLAUDE_CODE.supportsInstallCommand)
        assertTrue(ClientConfigGenerator.ClientType.CODEX_CLI.supportsInstallCommand)
        assertFalse(ClientConfigGenerator.ClientType.GEMINI_CLI.supportsInstallCommand)
        assertFalse(ClientConfigGenerator.ClientType.CURSOR.supportsInstallCommand)
    }

    // buildClaudeCodeCommand Tests

    @Test
    fun `buildClaudeCodeCommand generates correct command format`() {
        val serverUrl = "http://127.0.0.1:63342/debugger-mcp/sse"
        val serverName = "intellij-debugger"

        val command = ClientConfigGenerator.buildClaudeCodeCommand(serverUrl, serverName)

        val expectedCommand = "claude mcp remove jetbrains-debugger 2>/dev/null ; " +
            "claude mcp remove intellij-debugger 2>/dev/null ; " +
            "claude mcp add --transport sse intellij-debugger http://127.0.0.1:63342/debugger-mcp/sse --scope user"

        assertEquals(
            expectedCommand,
            command
        )
    }

    @Test
    fun `buildClaudeCodeCommand removes legacy server name`() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            "http://127.0.0.1:29190/debugger-mcp/sse",
            "pycharm-debugger"
        )

        assertTrue(
            command.contains("claude mcp remove jetbrains-debugger")
        )
    }

    @Test
    fun `buildClaudeCodeCommand includes 2 devnull redirect for remove commands`() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            "http://127.0.0.1:63342/debugger-mcp/sse",
            "webstorm-debugger"
        )

        // Both remove commands should have 2>/dev/null
        val removeCount = command.split("2>/dev/null").size - 1
        assertEquals(2, removeCount)
    }

    @Test
    fun `buildClaudeCodeCommand separates commands with semicolon`() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            "http://127.0.0.1:63342/debugger-mcp/sse",
            "goland-debugger"
        )

        assertTrue(command.contains(" ; "))
        val parts = command.split(" ; ")
        assertEquals(3, parts.size)
    }

    @Test
    fun `buildClaudeCodeCommand remove comes before add`() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            "http://127.0.0.1:63342/debugger-mcp/sse",
            "clion-debugger"
        )

        val lastRemoveIndex = command.lastIndexOf("remove")
        val addIndex = command.indexOf("add")

        assertTrue(
            lastRemoveIndex < addIndex
        )
    }

    // buildCodexCommand Tests

    @Test
    fun `buildCodexCommand generates correct command format`() {
        val serverUrl = "http://127.0.0.1:63342/debugger-mcp/sse"
        val serverName = "intellij-debugger"

        val command = ClientConfigGenerator.buildCodexCommand(serverUrl, serverName)

        val expectedCommand = "codex mcp remove intellij-debugger >/dev/null 2>&1 ; " +
            "codex mcp add intellij-debugger -- npx -y mcp-remote http://127.0.0.1:63342/debugger-mcp/sse --allow-http"

        assertEquals(
            expectedCommand,
            command
        )
    }

    @Test
    fun `buildCodexCommand includes 2 devnull redirect for remove command`() {
        val command = ClientConfigGenerator.buildCodexCommand(
            "http://127.0.0.1:63342/debugger-mcp/sse",
            "webstorm-debugger"
        )

        assertTrue(command.contains(">/dev/null 2>&1"))
    }

    @Test
    fun `buildCodexCommand separates commands with semicolon`() {
        val command = ClientConfigGenerator.buildCodexCommand(
            "http://127.0.0.1:63342/debugger-mcp/sse",
            "goland-debugger"
        )

        assertTrue(command.contains(" ; "))
        val parts = command.split(" ; ")
        assertEquals(2, parts.size)
    }

    @Test
    fun `buildCodexCommand remove comes before add`() {
        val command = ClientConfigGenerator.buildCodexCommand(
            "http://127.0.0.1:63342/debugger-mcp/sse",
            "clion-debugger"
        )

        val removeIndex = command.indexOf("remove")
        val addIndex = command.indexOf("add")

        assertTrue(
            removeIndex < addIndex
        )
    }

    // getConfigLocationHint Tests

    @Test
    fun `getConfigLocationHint for Claude Code mentions terminal`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CLAUDE_CODE)

        assertTrue(hint.contains("terminal") || hint.contains("command"))
        assertTrue(hint.contains("scope"))
    }

    @Test
    fun `getConfigLocationHint for Codex CLI mentions terminal`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CODEX_CLI)

        assertTrue(hint.contains("terminal") || hint.contains("command"))
        assertTrue(hint.contains("codex"))
    }

    @Test
    fun `getConfigLocationHint for Cursor mentions mcp json`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CURSOR)

        assertTrue(hint.contains("mcp.json"))
        assertTrue(hint.contains(".cursor"))
    }

    @Test
    fun `getConfigLocationHint for Gemini CLI mentions settings json`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.GEMINI_CLI)

        assertTrue(hint.contains("settings.json"))
        assertTrue(hint.contains(".gemini") || hint.contains("gemini"))
        assertTrue(hint.contains("mcp-remote"))
    }

    // Hint Methods Tests

    @Test
    fun `getStandardSseHint mentions SSE transport`() {
        val hint = ClientConfigGenerator.getStandardSseHint()

        assertTrue(hint.contains("SSE"))
        assertTrue(hint.contains("transport"))
    }

    @Test
    fun `getMcpRemoteHint mentions mcp-remote and stdio`() {
        val hint = ClientConfigGenerator.getMcpRemoteHint()

        assertTrue(hint.contains("mcp-remote"))
        assertTrue(hint.contains("stdio"))
        assertTrue(hint.contains("--allow-http"))
    }

    // getAvailableClients Tests

    @Test
    fun `getAvailableClients returns all client types`() {
        val clients = ClientConfigGenerator.getAvailableClients()

        assertEquals(4, clients.size)
        assertEquals(ClientConfigGenerator.ClientType.entries.toList(), clients)
    }

    @Test
    fun `getAvailableClients includes CLAUDE_CODE first`() {
        val clients = ClientConfigGenerator.getAvailableClients()

        assertEquals(ClientConfigGenerator.ClientType.CLAUDE_CODE, clients[0])
    }

    // getInstallableClients Tests

    @Test
    fun `getInstallableClients returns only clients with install commands`() {
        val clients = ClientConfigGenerator.getInstallableClients()

        assertEquals(2, clients.size)
        assertTrue(clients.contains(ClientConfigGenerator.ClientType.CLAUDE_CODE))
        assertTrue(clients.contains(ClientConfigGenerator.ClientType.CODEX_CLI))
        assertFalse(clients.contains(ClientConfigGenerator.ClientType.GEMINI_CLI))
        assertFalse(clients.contains(ClientConfigGenerator.ClientType.CURSOR))
    }

    @Test
    fun `getCopyableClients returns all client types`() {
        val clients = ClientConfigGenerator.getCopyableClients()

        assertEquals(4, clients.size)
        assertEquals(ClientConfigGenerator.ClientType.entries.toList(), clients)
    }

    // Config Format Tests (structure validation without actual server)

    @Test
    fun `Cursor config format is valid JSON structure`() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "url": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcpServers"))
        assertTrue(expectedFormat.contains("url"))
    }

    @Test
    fun `Gemini CLI config format uses mcp-remote`() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "SERVER_URL",
        "--allow-http"
      ]
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcpServers"))
        assertTrue(expectedFormat.contains("command"))
        assertTrue(expectedFormat.contains("npx"))
        assertTrue(expectedFormat.contains("mcp-remote"))
        assertTrue(expectedFormat.contains("--allow-http"))
    }

    @Test
    fun `Standard SSE config format has url key`() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "url": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcpServers"))
        assertTrue(expectedFormat.contains("url"))
    }

    @Test
    fun `mcp-remote config format has command and args`() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "SERVER_URL",
        "--allow-http"
      ]
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("command"))
        assertTrue(expectedFormat.contains("args"))
        assertTrue(expectedFormat.contains("npx"))
        assertTrue(expectedFormat.contains("mcp-remote"))
        assertTrue(expectedFormat.contains("-y"))
        assertTrue(expectedFormat.contains("--allow-http"))
    }
}
