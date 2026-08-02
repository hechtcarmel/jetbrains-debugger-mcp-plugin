package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the boundary-validation contract of [ToolArguments]: which inputs pass, which fail, and
 * the exact message for each failure class. The messages are client-facing — a model reads them
 * to correct its next call — so they are asserted verbatim, in the same spirit as the pinned
 * "Missing required parameter: <name>" strings this helper preserves.
 */
class ToolArgumentsTest {

    private fun args(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject(builder)

    private fun failureMessage(block: () -> Unit): String {
        try {
            block()
        } catch (e: ToolExecutionError) {
            return e.message
        }
        throw AssertionError("Expected ToolExecutionError, but nothing was thrown")
    }

    // ── requireString ───────────────────────────────────────────────────────────────────

    @Test
    fun `requireString returns the value`() {
        assertEquals("x", ToolArguments.requireString(args { put("name", "x") }, "name"))
    }

    @Test
    fun `requireString keeps the pinned missing-parameter message`() {
        assertEquals(
            "Missing required parameter: file_path",
            failureMessage { ToolArguments.requireString(args { }, "file_path") }
        )
    }

    @Test
    fun `requireString treats explicit JSON null as missing`() {
        assertEquals(
            "Missing required parameter: file_path",
            failureMessage { ToolArguments.requireString(args { put("file_path", JsonNull) }, "file_path") }
        )
    }

    @Test
    fun `requireString rejects a number`() {
        assertEquals(
            "Invalid type for parameter: expression (expected string)",
            failureMessage { ToolArguments.requireString(args { put("expression", 42) }, "expression") }
        )
    }

    @Test
    fun `requireString rejects an object`() {
        assertEquals(
            "Invalid type for parameter: expression (expected string)",
            failureMessage {
                ToolArguments.requireString(args { putJsonObject("expression") { } }, "expression")
            }
        )
    }

    // ── requireInt / optionalInt ────────────────────────────────────────────────────────

    @Test
    fun `requireInt returns the value`() {
        assertEquals(5, ToolArguments.requireInt(args { put("line", 5) }, "line"))
    }

    @Test
    fun `requireInt keeps the pinned missing-parameter message`() {
        assertEquals(
            "Missing required parameter: line",
            failureMessage { ToolArguments.requireInt(args { }, "line") }
        )
    }

    @Test
    fun `requireInt rejects a stringified number as a type error not a missing parameter`() {
        assertEquals(
            "Invalid type for parameter: line (expected integer)",
            failureMessage { ToolArguments.requireInt(args { put("line", "42") }, "line") }
        )
    }

    @Test
    fun `requireInt rejects a non-integer number`() {
        assertEquals(
            "Invalid type for parameter: line (expected integer)",
            failureMessage { ToolArguments.requireInt(args { put("line", 1.5) }, "line") }
        )
    }

    @Test
    fun `requireInt reports a lower bound`() {
        assertEquals(
            "Invalid value for parameter: frame_index (must be >= 0)",
            failureMessage { ToolArguments.requireInt(args { put("frame_index", -1) }, "frame_index", min = 0) }
        )
    }

    @Test
    fun `optionalInt reports a two-sided range`() {
        assertEquals(
            "Invalid value for parameter: max_frames (must be between 1 and 200)",
            failureMessage {
                ToolArguments.optionalInt(args { put("max_frames", 0) }, "max_frames", default = 50, min = 1, max = 200)
            }
        )
    }

    @Test
    fun `optionalInt falls back to the default when absent`() {
        assertEquals(50, ToolArguments.optionalInt(args { }, "max_frames", default = 50, min = 1, max = 200))
    }

    @Test
    fun `optionalInt accepts a boundary value`() {
        assertEquals(200, ToolArguments.optionalInt(args { put("max_frames", 200) }, "max_frames", default = 50, min = 1, max = 200))
    }

    // ── optionalBoolean ─────────────────────────────────────────────────────────────────

    @Test
    fun `optionalBoolean returns the value and the default`() {
        assertFalse(ToolArguments.optionalBoolean(args { put("enabled", false) }, "enabled", default = true))
        assertTrue(ToolArguments.optionalBoolean(args { }, "enabled", default = true))
    }

    @Test
    fun `optionalBoolean rejects a stringified boolean`() {
        assertEquals(
            "Invalid type for parameter: enabled (expected boolean)",
            failureMessage { ToolArguments.optionalBoolean(args { put("enabled", "true") }, "enabled", default = true) }
        )
    }

    // ── optionalEnum ────────────────────────────────────────────────────────────────────

    @Test
    fun `optionalEnum accepts a listed value and normalizes case`() {
        val allowed = listOf("all", "thread", "none")
        assertEquals("none", ToolArguments.optionalEnum(args { put("suspend_policy", "none") }, "suspend_policy", allowed))
        // The old suspend_policy code lowercased before matching; that tolerance is preserved.
        assertEquals("none", ToolArguments.optionalEnum(args { put("suspend_policy", "None") }, "suspend_policy", allowed))
        assertNull(ToolArguments.optionalEnum(args { }, "suspend_policy", allowed))
    }

    @Test
    fun `optionalEnum rejects an unknown value instead of silently defaulting`() {
        assertEquals(
            "Invalid value for parameter: suspend_policy (must be one of: all, thread, none)",
            failureMessage {
                ToolArguments.optionalEnum(args { put("suspend_policy", "banana") }, "suspend_policy", listOf("all", "thread", "none"))
            }
        )
    }

    // ── optionalStringList ──────────────────────────────────────────────────────────────

    @Test
    fun `optionalStringList parses an array of strings`() {
        val arguments = args {
            put("breakpoint_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a")); add(kotlinx.serialization.json.JsonPrimitive("b")) })
        }
        assertEquals(listOf("a", "b"), ToolArguments.optionalStringList(arguments, "breakpoint_ids"))
        assertNull(ToolArguments.optionalStringList(args { }, "breakpoint_ids"))
    }

    @Test
    fun `optionalStringList rejects a non-array and a non-string element`() {
        assertEquals(
            "Invalid type for parameter: breakpoint_ids (expected array of strings)",
            failureMessage { ToolArguments.optionalStringList(args { put("breakpoint_ids", "a") }, "breakpoint_ids") }
        )
        assertEquals(
            "Invalid type for parameter: breakpoint_ids (expected array of strings)",
            failureMessage {
                ToolArguments.optionalStringList(
                    args { put("breakpoint_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(1)) }) },
                    "breakpoint_ids"
                )
            }
        )
    }
}
