package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandStatus
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the fact that calling a tool over HTTP populates the tool window's command history.
 *
 * ## Why this needs its own test
 *
 * The recording lives inside `McpToolBridge` — the tool layer knows nothing
 * about it. When the MCP SDK migration deletes that handler, history recording disappears for
 * all 23 tools with **no compile error and no other failing test**: the tool window simply stays
 * empty forever and nobody notices until a user reports it.
 *
 * The existing `CommandHistoryServiceTest` cannot catch this. It calls `recordCommand` directly,
 * so it proves the store works while saying nothing about whether anything still calls it.
 */
class CommandHistoryRecordingTest : McpHttpTestCase() {

    private fun history() = CommandHistoryService.getInstance(project)

    override fun setUp() {
        super.setUp()
        history().clearHistory()
    }

    fun `test a successful tool call is recorded with its parameters and duration`() {
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall("list_breakpoints", """{"project_path":"${project.basePath}"}"""))

        val entries = history().entries
        assertEquals("Exactly one command should have been recorded", 1, entries.size)

        val entry = entries.single()
        assertEquals("list_breakpoints", entry.toolName)
        assertEquals(CommandStatus.SUCCESS, entry.status)
        assertEquals(
            "The recorded parameters must be the arguments the client sent",
            project.basePath,
            entry.parameters["project_path"]?.jsonPrimitive?.content
        )
        assertNotNull("A completed command must carry a duration", entry.durationMs)
        assertNotNull("A successful command stores its result text", entry.result)
        assertNull("A successful command must not carry an error", entry.error)
    }

    fun `test a failing tool call is recorded as an error`() {
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall("get_variables"))

        val entry = history().entries.single()
        assertEquals("get_variables", entry.toolName)
        assertEquals(CommandStatus.ERROR, entry.status)
        assertNotNull("A failed command stores the failure text", entry.error)
        assertNull("A failed command must not carry a result", entry.result)
    }

    fun `test history is newest first`() {
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall("list_breakpoints"))
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall("list_debug_sessions"))
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall("list_threads"))

        assertEquals(
            listOf("list_threads", "list_debug_sessions", "list_breakpoints"),
            history().entries.map { it.toolName }
        )
    }

    /**
     * Only `tools/call` is recorded. `tools/list`, `initialize` and `ping` are protocol traffic,
     * not user-visible commands, and flooding the tool window with them would be a regression.
     */
    fun `test protocol methods are not recorded as commands`() {
        post(McpConstants.MCP_ENDPOINT_PATH, rpc("initialize", params = "{}"))
        post(McpConstants.MCP_ENDPOINT_PATH, rpc("tools/list"))
        post(McpConstants.MCP_ENDPOINT_PATH, rpc("ping"))

        assertEquals("Protocol traffic must not appear in command history", 0, history().entries.size)
    }

    /**
     * An unknown tool is rejected before dispatch, so nothing is recorded. Pinned because the SDK
     * migration turns unknown tools into `isError` results, which could easily start recording
     * them.
     */
    fun `test an unknown tool is not recorded`() {
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall("no_such_tool"))

        assertEquals(0, history().entries.size)
    }

    fun `test recording works on the streamable transport too`() {
        val sessionId = initializeStreamable()
        post(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH, toolCall("list_breakpoints"), sessionHeaders(sessionId))

        assertEquals(
            "History must be transport-independent",
            listOf("list_breakpoints"),
            history().entries.map { it.toolName }
        )
    }
}
