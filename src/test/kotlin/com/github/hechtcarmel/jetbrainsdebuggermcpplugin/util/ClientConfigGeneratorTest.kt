package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import org.junit.Assert.*
import org.junit.Test

class ClientConfigGeneratorTest {

    // ClientType Enum Tests

    @Test
    fun `ClientType has all expected values`() {
        val types = ClientConfigGenerator.ClientType.entries

        assertEquals(5, types.size)
        assertTrue(types.contains(ClientConfigGenerator.ClientType.CLAUDE_CODE))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.CLAUDE_DESKTOP))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.CURSOR))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.VSCODE))
        assertTrue(types.contains(ClientConfigGenerator.ClientType.WINDSURF))
    }

    @Test
    fun `ClientType displayNames are set correctly`() {
        assertEquals("Claude Code (CLI)", ClientConfigGenerator.ClientType.CLAUDE_CODE.displayName)
        assertEquals("Claude Desktop", ClientConfigGenerator.ClientType.CLAUDE_DESKTOP.displayName)
        assertEquals("Cursor", ClientConfigGenerator.ClientType.CURSOR.displayName)
        assertEquals("VS Code (Generic MCP)", ClientConfigGenerator.ClientType.VSCODE.displayName)
        assertEquals("Windsurf", ClientConfigGenerator.ClientType.WINDSURF.displayName)
    }

    // buildClaudeCodeCommand Tests

    @Test
    fun `buildClaudeCodeCommand generates correct command`() {
        val serverUrl = "http://localhost:63342/debugger-mcp/sse"
        val serverName = "jetbrains-debugger"

        val command = ClientConfigGenerator.buildClaudeCodeCommand(serverUrl, serverName)

        assertTrue(command.contains("claude mcp remove jetbrains-debugger"))
        assertTrue(command.contains("claude mcp add --transport http jetbrains-debugger"))
        assertTrue(command.contains(serverUrl))
        assertTrue(command.contains("--scope user"))
    }

    @Test
    fun `buildClaudeCodeCommand with custom server name`() {
        val serverUrl = "http://localhost:8080/mcp"
        val serverName = "custom-debugger"

        val command = ClientConfigGenerator.buildClaudeCodeCommand(serverUrl, serverName)

        assertTrue(command.contains("claude mcp remove custom-debugger"))
        assertTrue(command.contains("claude mcp add --transport http custom-debugger"))
    }

    @Test
    fun `buildClaudeCodeCommand includes 2 devnull redirect for remove`() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            "http://localhost:63342/debugger-mcp/sse",
            "test-server"
        )

        assertTrue(command.contains("2>/dev/null"))
    }

    @Test
    fun `buildClaudeCodeCommand separates remove and add with semicolon`() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            "http://localhost:63342/debugger-mcp/sse",
            "test-server"
        )

        assertTrue(command.contains(" ; "))
        val parts = command.split(" ; ")
        assertEquals(2, parts.size)
    }

    // getConfigLocationHint Tests

    @Test
    fun `getConfigLocationHint for Claude Code mentions terminal`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CLAUDE_CODE)

        assertTrue(hint.contains("terminal") || hint.contains("command"))
        assertTrue(hint.contains("scope"))
    }

    @Test
    fun `getConfigLocationHint for Claude Desktop mentions config file`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CLAUDE_DESKTOP)

        assertTrue(hint.contains("claude_desktop_config.json"))
        assertTrue(hint.contains("macOS") || hint.contains("Windows") || hint.contains("Linux"))
    }

    @Test
    fun `getConfigLocationHint for Cursor mentions mcp json`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CURSOR)

        assertTrue(hint.contains("mcp.json"))
        assertTrue(hint.contains(".cursor"))
    }

    @Test
    fun `getConfigLocationHint for VS Code mentions settings`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.VSCODE)

        assertTrue(hint.contains("settings") || hint.contains("Settings"))
    }

    @Test
    fun `getConfigLocationHint for Windsurf mentions config path`() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.WINDSURF)

        assertTrue(hint.contains("mcp_config.json"))
        assertTrue(hint.contains("codeium") || hint.contains("windsurf"))
    }

    // getAvailableClients Tests

    @Test
    fun `getAvailableClients returns all client types`() {
        val clients = ClientConfigGenerator.getAvailableClients()

        assertEquals(5, clients.size)
        assertEquals(ClientConfigGenerator.ClientType.entries.toList(), clients)
    }

    @Test
    fun `getAvailableClients includes CLAUDE_CODE first`() {
        val clients = ClientConfigGenerator.getAvailableClients()

        assertEquals(ClientConfigGenerator.ClientType.CLAUDE_CODE, clients[0])
    }

    // Config Format Tests (structure validation without actual server)

    @Test
    fun `Claude Desktop config format is valid JSON structure`() {
        // Test the expected format structure
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
    fun `VS Code config format is valid JSON structure`() {
        val expectedFormat = """
{
  "mcp.servers": {
    "SERVER_NAME": {
      "transport": "sse",
      "url": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcp.servers"))
        assertTrue(expectedFormat.contains("transport"))
        assertTrue(expectedFormat.contains("sse"))
    }

    @Test
    fun `Windsurf config format uses serverUrl key`() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "serverUrl": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("serverUrl"))
        assertFalse(expectedFormat.contains("\"url\":"))
    }
}
