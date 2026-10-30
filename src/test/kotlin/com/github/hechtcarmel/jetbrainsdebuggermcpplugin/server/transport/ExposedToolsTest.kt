package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpToolBridge
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExposedToolsTest : McpHttpTestCase() {
    fun `test disabling and enabling tools changes list and dispatch in existing session`() {
        val path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        val session = initializeStreamable()
        val headers = sessionHeaders(session)
        val disabled = setOf("jump_to_line", "execute_run_configuration")
        val bridge = McpToolBridge()
        fun names(): Set<String> = post(path, rpc("tools/list"), headers).jsonBody()["result"]!!
            .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

        val original = names()
        assertTrue(original.containsAll(disabled))
        McpServerFactory.updateTools(mcpServer, registry, bridge, emptySet(), disabled)
        assertEquals(original - disabled, names())
        val rejected = pumpingEdt { post(path, toolCall("jump_to_line"), headers) }.jsonBody()
        assertEquals("true", rejected["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)
        McpServerFactory.updateTools(mcpServer, registry, bridge, disabled, emptySet())
        assertEquals(original, names())
        // A registered tool reaches its own validation instead of being rejected as unknown.
        val restored = pumpingEdt { post(path, toolCall("jump_to_line"), headers) }.body()
        assertFalse(restored.contains("Unknown tool"))
    }
}
