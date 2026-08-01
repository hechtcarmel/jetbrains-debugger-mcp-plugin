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
        assertEquals("2025-03-26", McpConstants.MCP_PROTOCOL_VERSION)
        val regex = Regex("""\d{4}-\d{2}-\d{2}""")
        assertTrue(regex.matches(McpConstants.MCP_PROTOCOL_VERSION))
    }

    @Test
    fun `getServerName returns IDE-specific name`() {
        val serverName = McpConstants.getServerName()
        assertTrue(serverName.isNotEmpty())
        assertTrue(serverName.endsWith("-debugger"))
    }

    @Test
    fun `server version follows semver pattern`() {
        val semverRegex = Regex("""\d+\.\d+\.\d+(-[\w.]+)?""")
        assertTrue(semverRegex.matches(McpConstants.SERVER_VERSION))
    }

    /**
     * `SERVER_VERSION` is what the plugin reports to MCP clients in `initialize`, so it has to be
     * the version the plugin actually ships.
     *
     * It had drifted to `4.0.0` while the plugin shipped 4.3.1 — and the previous version of this
     * test asserted the literal `"4.0.0"`, which meant the suite actively prevented the constant
     * from being corrected. Comparing against `gradle.properties` instead makes the constant
     * impossible to leave behind.
     */
    @Test
    fun `server version matches the shipped plugin version`() {
        val pluginVersion = java.io.File("gradle.properties").readLines()
            .firstOrNull { it.trimStart().startsWith("pluginVersion") }
            ?.substringAfter('=')?.trim()

        assertNotNull("Could not read pluginVersion from gradle.properties", pluginVersion)
        assertEquals(
            "McpConstants.SERVER_VERSION is reported to every MCP client during initialize and " +
                "must match gradle.properties' pluginVersion.",
            pluginVersion,
            McpConstants.SERVER_VERSION
        )
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

}
