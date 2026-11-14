package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport.McpHttpTestCase
import kotlinx.serialization.json.*

/** Real registry/HTTP boundary: lifecycle diagnostics and exact ID/argument validation. */
class RunSessionToolsBehaviorTest : McpHttpTestCase() {
    private fun call(tool: String, arguments: String = "{}") =
        pumpingEdt { post(McpConstants.MCP_ENDPOINT_PATH, toolCall(tool, arguments)) }
            .jsonBody()["result"]!!.jsonObject

    fun `test failed startup is discoverable and readable as diagnostics over HTTP`() {
        val service = RunSessionService.getInstance(project)
        val id = service.reserve("Failed Application")
        service.failed(id, "Main class missing")
        val listed = call("list_run_sessions")["structuredContent"]!!.jsonObject
        val session = listed["sessions"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == id }
        assertEquals("run", session["mode"]!!.jsonPrimitive.content)
        assertEquals("failed_start", session["state"]!!.jsonPrimitive.content)
        val result = call("get_run_console_output", """{"session_id":"$id"}""")
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        val body = result["structuredContent"]!!.jsonObject
        assertEquals(JsonNull, body["console"])
        assertTrue(body["consoleError"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("Main class missing", body["session"]!!.jsonObject["failureMessage"]!!.jsonPrimitive.content)
    }

    fun `test explicit Run id is mandatory and Debug tools reject it`() {
        assertTrue(call("get_run_console_output")["isError"]!!.jsonPrimitive.boolean)
        assertTrue(call("stop_run_session", """{"session_id":"debug-id"}""")["isError"]!!.jsonPrimitive.boolean)
        assertTrue(call("get_console_output", """{"session_id":"run:unknown"}""")["isError"]!!.jsonPrimitive.boolean)
        assertTrue(call("get_run_console_output", """{"session_id":"run:unknown"}""")["isError"]!!.jsonPrimitive.boolean)
    }

    fun `test pagination bounds reject invalid numbers and types before session lookup`() {
        for (fragment in listOf("\"lines\":0", "\"lines\":1001", "\"offset\":-1", "\"max_chars\":100001", "\"max_chars\":\"12\"")) {
            val result = call("get_run_console_output", """{"session_id":"run:unknown",$fragment}""")
            assertTrue(result["isError"]!!.jsonPrimitive.boolean)
            val error = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(error, error.startsWith("Invalid "))
        }
    }
}
