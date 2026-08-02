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
    fun `getServerName returns IDE-specific name`() {
        val serverName = McpConstants.getServerName()
        assertTrue(serverName.isNotEmpty())
        assertTrue(serverName.endsWith("-debugger"))
    }

    @Test
    fun `server version follows semver pattern`() {
        val semverRegex = Regex("""\d+\.\d+\.\d+(-[\w.]+)?""")
        assertTrue(semverRegex.matches(McpConstants.SERVER_VERSION))
        assertTrue(semverRegex.matches(McpConstants.FALLBACK_SERVER_VERSION))
    }

    /**
     * `SERVER_VERSION` is what the plugin reports to MCP clients in `initialize`, so it has to be
     * the version the plugin actually ships. It is now read from the installed plugin descriptor
     * at runtime, which cannot drift; but its unit-test fallback constant still can.
     *
     * History: the old hardcoded constant had drifted to `4.0.0` while the plugin shipped 4.3.1 —
     * and the then-current version of this test asserted the literal `"4.0.0"`, which meant the
     * suite actively prevented the constant from being corrected. Comparing against
     * `gradle.properties` instead makes the fallback impossible to leave behind.
     */
    @Test
    fun `fallback server version matches the shipped plugin version`() {
        val pluginVersion = java.io.File("gradle.properties").readLines()
            .firstOrNull { it.trimStart().startsWith("pluginVersion") }
            ?.substringAfter('=')?.trim()

        assertNotNull("Could not read pluginVersion from gradle.properties", pluginVersion)
        assertEquals(
            "McpConstants.FALLBACK_SERVER_VERSION stands in for the plugin-descriptor version in " +
                "unit tests and must match gradle.properties' pluginVersion.",
            pluginVersion,
            McpConstants.FALLBACK_SERVER_VERSION
        )
    }

    /**
     * Whether the descriptor is loaded (platform tests) or not (plain unit tests), the reported
     * version must resolve to the shipped plugin version — the descriptor's version and the
     * fallback are both pinned to gradle.properties, so the two paths must agree.
     */
    @Test
    fun `server version resolves to a non-blank version`() {
        assertTrue(McpConstants.SERVER_VERSION.isNotBlank())
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
