package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport.McpHttpTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives argument validation (C6) through real HTTP: what an MCP client actually receives for
 * each failure class, asserted verbatim because the message is the only signal a model can act
 * on.
 *
 * Three kinds of string are pinned here:
 * - preserved pre-existing strings ("Missing required parameter: <name>", "Session not found:
 *   <id>", "No active debug session", "timeout must be positive") — these predate `ToolArguments`
 *   and must never change,
 * - the new wrong-type class ("Invalid type for parameter: <name> (expected <type>)"), which
 *   previously surfaced as a misleading missing-parameter error or silent coercion,
 * - the new out-of-range/enum class ("Invalid value for parameter: <name> (...)"), which
 *   previously escaped as a raw exception or silently defaulted (`suspend_policy: "banana"`
 *   became ALL).
 */
class ToolArgumentBoundaryTest : McpHttpTestCase() {

    private fun callTool(name: String, arguments: String): JsonObject =
        pumpingEdt { post(McpConstants.MCP_ENDPOINT_PATH, toolCall(name, arguments)) }
            .jsonBody()["result"]!!.jsonObject

    private fun errorText(result: JsonObject): String {
        assertTrue(
            "Expected an isError result, got: $result",
            result["isError"]!!.jsonPrimitive.booleanOrNull!!
        )
        return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    // ── Preserved pre-existing strings ──────────────────────────────────────────────────

    fun `test a missing required parameter keeps its pinned message`() {
        assertEquals(
            "Missing required parameter: frame_index",
            errorText(callTool("select_stack_frame", "{}"))
        )
        assertEquals(
            "Missing required parameter: timeout",
            errorText(callTool("wait_for_pause", "{}"))
        )
    }

    fun `test wait_for_pause keeps its pinned positive-timeout message`() {
        assertEquals("timeout must be positive", errorText(callTool("wait_for_pause", """{"timeout":0}""")))
    }

    fun `test session resolution keeps its pinned messages through the execution base class`() {
        assertEquals("No active debug session", errorText(callTool("step_over", "{}")))
        assertEquals("Session not found: bogus", errorText(callTool("resume_execution", """{"session_id":"bogus"}""")))
    }

    // ── Wrong JSON kind ─────────────────────────────────────────────────────────────────

    fun `test a stringified integer is a type error not a missing parameter`() {
        assertEquals(
            "Invalid type for parameter: line (expected integer)",
            errorText(callTool("set_breakpoint", """{"file_path":"/nope/Missing.java","line":"5"}"""))
        )
    }

    fun `test a numeric value for a string parameter is a type error`() {
        assertEquals(
            "Invalid type for parameter: expression (expected string)",
            errorText(callTool("evaluate_expression", """{"expression":42}"""))
        )
    }

    fun `test a non-array breakpoint_ids is a type error`() {
        assertEquals(
            "Invalid type for parameter: breakpoint_ids (expected array of strings)",
            errorText(callTool("wait_for_pause", """{"timeout":1,"breakpoint_ids":"abc"}"""))
        )
    }

    // ── Out of range / unknown enum value ───────────────────────────────────────────────

    fun `test a negative frame_index is a range error instead of a raw exception`() {
        assertEquals(
            "Invalid value for parameter: frame_index (must be >= 0)",
            errorText(callTool("get_variables", """{"frame_index":-1}"""))
        )
    }

    fun `test an out-of-range max_frames names its bounds`() {
        assertEquals(
            "Invalid value for parameter: max_frames (must be between 1 and 200)",
            errorText(callTool("get_stack_trace", """{"max_frames":0}"""))
        )
    }

    fun `test an unknown suspend_policy is rejected instead of silently becoming ALL`() {
        assertEquals(
            "Invalid value for parameter: suspend_policy (must be one of: all, thread, none)",
            errorText(callTool("set_breakpoint", """{"file_path":"/nope/Missing.java","line":1,"suspend_policy":"banana"}"""))
        )
    }
}
