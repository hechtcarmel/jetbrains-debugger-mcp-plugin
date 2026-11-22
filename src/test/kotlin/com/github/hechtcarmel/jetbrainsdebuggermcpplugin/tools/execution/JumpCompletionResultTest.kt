package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JumpCompletionResultTest {
    @Test fun `unverified result retains explicit error flag without claiming failure or new state`() {
        val message = JumpRequestObservation.unverifiedMessage(JumpRequestObservation.Invocation.RETURNED)
        val result = JumpCompletionResult.unverified("requested-session", message)
        assertEquals(true, result.isError)
        val payload = requireNotNull(result.structuredContent)
        assertEquals(setOf("sessionId", "action", "status", "message"), payload.keys)
        assertEquals("requested-session", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("jump_to_line", payload["action"]!!.jsonPrimitive.content)
        assertEquals("completion_unverified", payload["status"]!!.jsonPrimitive.content)
        assertEquals(message, payload["message"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("newState"))
        assertEquals(payload, Json.parseToJsonElement((result.content.single() as TextContent).text))
        val wire = McpJson.encodeToString(CallToolResult.serializer(), result)
        assertTrue(wire.contains("\"isError\":true"))
        assertEquals(payload, Json.parseToJsonElement(wire).jsonObject["structuredContent"])
    }
}
