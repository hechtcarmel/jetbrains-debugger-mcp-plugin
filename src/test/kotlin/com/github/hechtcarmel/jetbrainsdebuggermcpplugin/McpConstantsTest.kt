package com.github.hechtcarmel.jetbrainsdebuggermcpplugin

import org.junit.Assert.*
import org.junit.Test

class McpConstantsTest {

    @Test
    fun `plugin name is set correctly`() {
        assertEquals("Debugger MCP Server", McpConstants.PLUGIN_NAME)
    }

    @Test
    fun `tool window id matches plugin name`() {
        assertEquals(McpConstants.PLUGIN_NAME, McpConstants.TOOL_WINDOW_ID)
    }

    @Test
    fun `notification group id matches plugin name`() {
        assertEquals(McpConstants.PLUGIN_NAME, McpConstants.NOTIFICATION_GROUP_ID)
    }

    @Test
    fun `settings display name matches plugin name`() {
        assertEquals(McpConstants.PLUGIN_NAME, McpConstants.SETTINGS_DISPLAY_NAME)
    }

    @Test
    fun `MCP endpoint path starts with slash`() {
        assertTrue(McpConstants.MCP_ENDPOINT_PATH.startsWith("/"))
        assertEquals("/debugger-mcp", McpConstants.MCP_ENDPOINT_PATH)
    }

    @Test
    fun `SSE endpoint path extends MCP endpoint path`() {
        assertTrue(McpConstants.SSE_ENDPOINT_PATH.startsWith(McpConstants.MCP_ENDPOINT_PATH))
        assertEquals("/debugger-mcp/sse", McpConstants.SSE_ENDPOINT_PATH)
    }

    @Test
    fun `JSON RPC version is 2_0`() {
        assertEquals("2.0", McpConstants.JSON_RPC_VERSION)
    }

    @Test
    fun `MCP protocol version is valid date format`() {
        assertEquals("2024-11-05", McpConstants.MCP_PROTOCOL_VERSION)
        val regex = Regex("""\d{4}-\d{2}-\d{2}""")
        assertTrue(regex.matches(McpConstants.MCP_PROTOCOL_VERSION))
    }

    @Test
    fun `server name is not empty`() {
        assertTrue(McpConstants.SERVER_NAME.isNotEmpty())
        assertEquals("jetbrains-debugger", McpConstants.SERVER_NAME)
    }

    @Test
    fun `server version follows semver pattern`() {
        val semverRegex = Regex("""\d+\.\d+\.\d+(-[\w.]+)?""")
        assertTrue(semverRegex.matches(McpConstants.SERVER_VERSION))
        assertEquals("1.0.0", McpConstants.SERVER_VERSION)
    }

    @Test
    fun `server description is comprehensive`() {
        assertTrue(McpConstants.SERVER_DESCRIPTION.isNotEmpty())
        assertTrue("Description should mention debug/debugging",
            McpConstants.SERVER_DESCRIPTION.contains("debug", ignoreCase = true))
        assertTrue("Description should mention breakpoint",
            McpConstants.SERVER_DESCRIPTION.contains("breakpoint", ignoreCase = true))
        assertTrue("Description should mention variable",
            McpConstants.SERVER_DESCRIPTION.contains("variable", ignoreCase = true))
    }

    @Test
    fun `agent rule text contains important keyword`() {
        assertTrue(McpConstants.AGENT_RULE_TEXT.startsWith("IMPORTANT"))
        assertTrue(McpConstants.AGENT_RULE_TEXT.contains("debugger"))
        assertTrue(McpConstants.AGENT_RULE_TEXT.contains("jetbrains-debugger"))
    }
}
