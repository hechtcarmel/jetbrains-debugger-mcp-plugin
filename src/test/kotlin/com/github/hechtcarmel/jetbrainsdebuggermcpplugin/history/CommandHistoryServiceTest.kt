package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CommandHistoryServiceTest : BasePlatformTestCase() {

    private lateinit var historyService: CommandHistoryService

    override fun setUp() {
        super.setUp()
        historyService = CommandHistoryService.getInstance(project)
        historyService.clearHistory()
    }

    override fun tearDown() {
        historyService.clearHistory()
        super.tearDown()
    }

    fun `test history starts empty`() {
        assertTrue(historyService.entries.isEmpty())
    }

    fun `test record command adds entry to history`() {
        val entry = createTestEntry("test_tool")

        historyService.recordCommand(entry)

        assertEquals(1, historyService.entries.size)
        assertEquals("test_tool", historyService.entries[0].toolName)
    }

    fun `test record command adds newest entries first`() {
        val entry1 = createTestEntry("tool_1")
        val entry2 = createTestEntry("tool_2")

        historyService.recordCommand(entry1)
        historyService.recordCommand(entry2)

        assertEquals(2, historyService.entries.size)
        assertEquals("tool_2", historyService.entries[0].toolName)
        assertEquals("tool_1", historyService.entries[1].toolName)
    }

    fun `test update command status sets success status`() {
        val entry = createTestEntry("update_test")
        historyService.recordCommand(entry)

        historyService.updateCommandStatus(
            id = entry.id,
            status = CommandStatus.SUCCESS,
            result = "Success result",
            durationMs = 100L
        )

        val updated = historyService.entries[0]
        assertEquals(CommandStatus.SUCCESS, updated.status)
        assertEquals("Success result", updated.result)
        assertEquals(100L, updated.durationMs)
        assertNull(updated.error)
    }

    fun `test update command status sets error status`() {
        val entry = createTestEntry("error_test")
        historyService.recordCommand(entry)

        historyService.updateCommandStatus(
            id = entry.id,
            status = CommandStatus.ERROR,
            result = "Error message",
            durationMs = 50L
        )

        val updated = historyService.entries[0]
        assertEquals(CommandStatus.ERROR, updated.status)
        assertEquals("Error message", updated.error)
        assertNull(updated.result)
        assertEquals(50L, updated.durationMs)
    }

    fun `test clear history removes all entries`() {
        historyService.recordCommand(createTestEntry("tool_1"))
        historyService.recordCommand(createTestEntry("tool_2"))
        historyService.recordCommand(createTestEntry("tool_3"))
        assertEquals(3, historyService.entries.size)

        historyService.clearHistory()

        assertTrue(historyService.entries.isEmpty())
    }

    fun `test get filtered history by tool name`() {
        historyService.recordCommand(createTestEntry("set_breakpoint"))
        historyService.recordCommand(createTestEntry("resume"))
        historyService.recordCommand(createTestEntry("set_breakpoint"))

        val filter = CommandFilter(toolName = "set_breakpoint")
        val filtered = historyService.getFilteredHistory(filter)

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.toolName == "set_breakpoint" })
    }

    fun `test get filtered history by status`() {
        val successEntry = createTestEntry("success_tool")
        val errorEntry = createTestEntry("error_tool")
        historyService.recordCommand(successEntry)
        historyService.recordCommand(errorEntry)

        historyService.updateCommandStatus(successEntry.id, CommandStatus.SUCCESS, "ok", 10L)
        historyService.updateCommandStatus(errorEntry.id, CommandStatus.ERROR, "failed", 20L)

        val filter = CommandFilter(status = CommandStatus.SUCCESS)
        val filtered = historyService.getFilteredHistory(filter)

        assertEquals(1, filtered.size)
        assertEquals(CommandStatus.SUCCESS, filtered[0].status)
    }

    fun `test get filtered history by search text in tool name`() {
        historyService.recordCommand(createTestEntry("set_breakpoint"))
        historyService.recordCommand(createTestEntry("list_breakpoints"))
        historyService.recordCommand(createTestEntry("resume"))

        val filter = CommandFilter(searchText = "breakpoint")
        val filtered = historyService.getFilteredHistory(filter)

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.toolName.contains("breakpoint") })
    }

    fun `test get unique tool names`() {
        historyService.recordCommand(createTestEntry("tool_b"))
        historyService.recordCommand(createTestEntry("tool_a"))
        historyService.recordCommand(createTestEntry("tool_b"))
        historyService.recordCommand(createTestEntry("tool_c"))

        val uniqueNames = historyService.getUniqueToolNames()

        assertEquals(3, uniqueNames.size)
        assertEquals(listOf("tool_a", "tool_b", "tool_c"), uniqueNames)
    }

    fun `test export to json produces valid json`() {
        historyService.recordCommand(createTestEntry("json_test"))

        val json = historyService.exportToJson()

        assertTrue(json.contains("json_test"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }

    fun `test export to csv produces valid csv`() {
        val entry = createTestEntry("csv_test")
        historyService.recordCommand(entry)
        historyService.updateCommandStatus(entry.id, CommandStatus.SUCCESS, "result", 100L)

        val csv = historyService.exportToCsv()
        val lines = csv.lines()

        assertEquals("ID,Timestamp,Tool,Status,Duration(ms),Result,Error", lines[0])
        assertTrue(lines[1].contains("csv_test"))
        assertTrue(lines[1].contains("SUCCESS"))
    }

    /**
     * Exercises the ring-buffer eviction in `trimHistoryIfNeeded`.
     *
     * The previous version of this test recorded 150 entries against the default limit of 1000 and
     * asserted `150 == 150` — it was named "trims to max size" while never crossing the threshold,
     * so the trimming logic was entirely unverified while appearing covered. The limit is lowered
     * here so eviction actually happens.
     */
    fun `test history evicts the oldest entries once the max size is exceeded`() {
        val settings = McpSettings.getInstance()
        val originalMax = settings.maxHistorySize
        settings.maxHistorySize = 5
        try {
            repeat(8) { i -> historyService.recordCommand(createTestEntry("tool_$i")) }

            val entries = historyService.entries
            assertEquals("History must be capped at the configured maximum", 5, entries.size)
            assertEquals(
                "Eviction must drop the oldest entries and keep the newest, newest-first",
                listOf("tool_7", "tool_6", "tool_5", "tool_4", "tool_3"),
                entries.map { it.toolName }
            )
        } finally {
            settings.maxHistorySize = originalMax
        }
    }

    fun `test history keeps every entry while under the max size`() {
        val settings = McpSettings.getInstance()
        val originalMax = settings.maxHistorySize
        settings.maxHistorySize = 10
        try {
            repeat(4) { i -> historyService.recordCommand(createTestEntry("tool_$i")) }
            assertEquals(4, historyService.entries.size)
        } finally {
            settings.maxHistorySize = originalMax
        }
    }

    private fun createTestEntry(toolName: String): CommandEntry {
        return CommandEntry(
            toolName = toolName,
            parameters = buildJsonObject {
                put("test", "value")
            }
        )
    }
}
