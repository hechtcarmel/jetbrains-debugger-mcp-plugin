package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpToolBridge
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

class ExposedToolsTest : McpHttpTestCase() {
    fun `test disabling and enabling tools changes list and dispatch in existing session`() {
        val path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        val session = initializeStreamable()
        val headers = sessionHeaders(session)
        val secondHeaders = sessionHeaders(initializeStreamable())
        val calls = AtomicInteger()
        val sentinel = object : McpTool {
            override val name = "test_sentinel"
            override val description = "Test dispatch"
            override val inputSchema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
            override val annotations = ToolAnnotationPresets.readOnly("Test sentinel")
            override suspend fun execute(project: Project, arguments: JsonObject): CallToolResult {
                calls.incrementAndGet()
                return CallToolResult(content = listOf(TextContent("sentinel executed")), isError = false)
            }
        }
        registry.register(sentinel)
        val bridge = McpToolBridge()
        // Register a sentinel through the same path used to re-enable a disabled tool.
        McpServerFactory.updateTools(mcpServer, registry, bridge, setOf(sentinel.name), emptySet())
        val disabled = setOf(sentinel.name, "jump_to_line", "execute_run_configuration")
        fun names(): Set<String> = post(path, rpc("tools/list"), headers).jsonBody()["result"]!!
            .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        fun call(name: String): JsonObject = pumpingEdt {
            post(path, toolCall(name, """{"project_path":"${project.basePath}"}"""), headers).jsonBody()["result"]!!.jsonObject
        }
        fun message(result: JsonObject): String = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

        val original = names()
        assertTrue(original.containsAll(disabled))
        McpServerFactory.updateTools(mcpServer, registry, bridge, emptySet(), disabled)
        assertEquals(original - disabled, names())
        val otherNames = post(path, rpc("tools/list"), secondHeaders).jsonBody()["result"]!!
            .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(original - disabled, otherNames)
        val stateless = post(McpConstants.MCP_ENDPOINT_PATH, toolCall("jump_to_line")).jsonBody()["result"]!!.jsonObject
        assertEquals("Tool jump_to_line not found", message(stateless))
        for (name in disabled) {
            val rejected = call(name)
            assertEquals("true", rejected["isError"]!!.jsonPrimitive.content)
            assertEquals("Tool $name not found", message(rejected))
        }
        assertEquals(0, calls.get())
        McpServerFactory.updateTools(mcpServer, registry, bridge, disabled, emptySet())
        assertEquals(original, names())
        val restored = call(sentinel.name)
        assertEquals("false", restored["isError"]!!.jsonPrimitive.content)
        assertEquals("sentinel executed", message(restored))
        assertEquals(1, calls.get())
    }
}

/** Simulates a saved selection loaded before the shared server is constructed. */
class InitiallyDisabledToolsTest : McpHttpTestCase() {
    override fun initialDisabledTools(registry: ToolRegistry): Set<String> = registry.getAllTools().map { it.name }.toSet()

    fun `test all tools can start disabled and be re-enabled`() {
        val path = McpConstants.MCP_ENDPOINT_PATH
        fun names(): Set<String> = post(path, rpc("tools/list")).jsonBody()["result"]!!.jsonObject["tools"]!!
            .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val disabled = registry.getAllTools().map { it.name }.toSet()
        assertTrue(names().isEmpty())
        val rejected = post(path, toolCall("jump_to_line")).jsonBody()["result"]!!.jsonObject
        assertEquals("Tool jump_to_line not found", rejected["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        McpServerFactory.updateTools(mcpServer, registry, McpToolBridge(), disabled, emptySet())
        assertEquals(disabled, names())
    }
}
