package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CommandModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // CommandEntry Tests

    @Test
    fun `CommandEntry has unique id on creation`() {
        val entry1 = CommandEntry(toolName = "tool1", parameters = buildJsonObject {})
        val entry2 = CommandEntry(toolName = "tool2", parameters = buildJsonObject {})

        assertNotEquals(entry1.id, entry2.id)
    }

    @Test
    fun `CommandEntry has timestamp on creation`() {
        val before = Instant.now()
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})
        val after = Instant.now()

        assertTrue(entry.timestamp >= before)
        assertTrue(entry.timestamp <= after)
    }

    @Test
    fun `CommandEntry defaults to PENDING status`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})

        assertEquals(CommandStatus.PENDING, entry.status)
    }

    @Test
    fun `CommandEntry result and error are null by default`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})

        assertNull(entry.result)
        assertNull(entry.error)
    }

    @Test
    fun `CommandEntry durationMs is null by default`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})

        assertNull(entry.durationMs)
    }

    @Test
    fun `CommandEntry status can be updated`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})

        entry.status = CommandStatus.SUCCESS
        assertEquals(CommandStatus.SUCCESS, entry.status)

        entry.status = CommandStatus.ERROR
        assertEquals(CommandStatus.ERROR, entry.status)
    }

    // CommandStatus Tests

    @Test
    fun `CommandStatus has three values`() {
        val statuses = CommandStatus.entries

        assertEquals(3, statuses.size)
        assertTrue(statuses.contains(CommandStatus.PENDING))
        assertTrue(statuses.contains(CommandStatus.SUCCESS))
        assertTrue(statuses.contains(CommandStatus.ERROR))
    }

    // CommandFilter Tests

    @Test
    fun `CommandFilter isEmpty returns true when all fields null`() {
        val filter = CommandFilter()

        assertTrue(filter.isEmpty())
    }

    @Test
    fun `CommandFilter isEmpty returns false when toolName set`() {
        val filter = CommandFilter(toolName = "test_tool")

        assertFalse(filter.isEmpty())
    }

    @Test
    fun `CommandFilter isEmpty returns false when status set`() {
        val filter = CommandFilter(status = CommandStatus.SUCCESS)

        assertFalse(filter.isEmpty())
    }

    @Test
    fun `CommandFilter isEmpty returns false when searchText set`() {
        val filter = CommandFilter(searchText = "search")

        assertFalse(filter.isEmpty())
    }

    @Test
    fun `CommandFilter isEmpty returns false when all fields set`() {
        val filter = CommandFilter(
            toolName = "test",
            status = CommandStatus.PENDING,
            searchText = "query"
        )

        assertFalse(filter.isEmpty())
    }

    // CommandEntryExport Tests

    @Test
    fun `CommandEntryExport serializes correctly`() {
        val export = CommandEntryExport(
            id = "test-id",
            timestamp = "2024-01-01T00:00:00Z",
            toolName = "test_tool",
            parameters = buildJsonObject { put("key", "value") },
            status = "SUCCESS",
            result = "Result text",
            error = null,
            durationMs = 100L,
            affectedFiles = listOf("/path/to/file")
        )

        val encoded = json.encodeToString(export)

        assertTrue(encoded.contains("\"id\":\"test-id\""))
        assertTrue(encoded.contains("\"toolName\":\"test_tool\""))
        assertTrue(encoded.contains("\"status\":\"SUCCESS\""))
        assertTrue(encoded.contains("\"durationMs\":100"))
    }

    @Test
    fun `toExport converts CommandEntry correctly`() {
        val entry = CommandEntry(
            toolName = "set_breakpoint",
            parameters = buildJsonObject {
                put("file", "/path/file.kt")
                put("line", 42)
            }
        )
        entry.status = CommandStatus.SUCCESS
        entry.result = "Breakpoint set"
        entry.durationMs = 50L

        val export = entry.toExport()

        assertEquals(entry.id, export.id)
        assertEquals(entry.timestamp.toString(), export.timestamp)
        assertEquals("set_breakpoint", export.toolName)
        assertEquals("SUCCESS", export.status)
        assertEquals("Breakpoint set", export.result)
        assertNull(export.error)
        assertEquals(50L, export.durationMs)
    }

    @Test
    fun `toExport handles error status correctly`() {
        val entry = CommandEntry(
            toolName = "resume",
            parameters = buildJsonObject {}
        )
        entry.status = CommandStatus.ERROR
        entry.error = "No active session"
        entry.durationMs = 5L

        val export = entry.toExport()

        assertEquals("ERROR", export.status)
        assertEquals("No active session", export.error)
        assertNull(export.result)
    }

    // CommandHistoryEvent Tests

    @Test
    fun `CommandAdded event contains entry`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})
        val event = CommandHistoryEvent.CommandAdded(entry)

        assertEquals(entry, event.entry)
    }

    @Test
    fun `CommandUpdated event contains entry`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})
        val event = CommandHistoryEvent.CommandUpdated(entry)

        assertEquals(entry, event.entry)
    }

    @Test
    fun `HistoryCleared is singleton object`() {
        val event1 = CommandHistoryEvent.HistoryCleared
        val event2 = CommandHistoryEvent.HistoryCleared

        assertSame(event1, event2)
    }

    // Affectedfiles Tests

    @Test
    fun `CommandEntry affectedFiles can be set`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})
        val files = listOf("/path/to/file1.kt", "/path/to/file2.kt")

        entry.affectedFiles = files

        assertEquals(files, entry.affectedFiles)
    }

    @Test
    fun `CommandEntry affectedFiles is null by default`() {
        val entry = CommandEntry(toolName = "test", parameters = buildJsonObject {})

        assertNull(entry.affectedFiles)
    }
}
