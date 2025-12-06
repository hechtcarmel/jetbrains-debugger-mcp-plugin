package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.integration

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * Integration tests for the MCP server functionality.
 * Tests the complete request/response flow through the JSON-RPC handler.
 */
class McpIntegrationTest : BasePlatformTestCase() {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var handler: JsonRpcHandler

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        handler = JsonRpcHandler(toolRegistry)
    }

    fun `test full initialize handshake`() = runBlocking {
        // Step 1: Initialize
        val initRequest = buildJsonRpcRequest("initialize", 1)
        val initResponse = handler.handleRequest(encodeRequest(initRequest))
        val initResult = parseResponse(initResponse)

        // Should have result (error may be null or missing, focus on result)
        assertNotNull("Initialize should return a result", initResult["result"])

        val result = initResult["result"]?.jsonObject
        assertNotNull(result?.get("protocolVersion"))
        assertNotNull(result?.get("serverInfo"))
        assertNotNull(result?.get("capabilities"))

        // Step 2: Initialized notification
        val initializedRequest = buildJsonRpcRequest("notifications/initialized", 2)
        val initializedResponse = handler.handleRequest(encodeRequest(initializedRequest))
        val initializedResult = parseResponse(initializedResponse)

        // Initialized returns empty result or result with id
        assertTrue("Response should have id or result",
            initializedResult["id"] != null || initializedResult["result"] != null)
    }

    fun `test tools discovery`() = runBlocking {
        val request = buildJsonRpcRequest("tools/list", 1)
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        assertNull(result["error"])

        val tools = result["result"]?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools)
        assertTrue("Should have registered tools", tools!!.size >= 20)

        // Verify tool structure
        val firstTool = tools[0].jsonObject
        assertNotNull(firstTool["name"])
        assertNotNull(firstTool["description"])
        assertNotNull(firstTool["inputSchema"])
    }

    fun `test list run configurations tool`() = runBlocking {
        val request = buildToolCallRequest("list_run_configurations", buildJsonObject {})
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Even with no configurations, should return a valid response
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)
    }

    fun `test list debug sessions tool`() = runBlocking {
        val request = buildToolCallRequest("list_debug_sessions", buildJsonObject {})
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should return a valid response (possibly empty sessions list)
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)
    }

    fun `test list breakpoints tool`() = runBlocking {
        val request = buildToolCallRequest("list_breakpoints", buildJsonObject {})
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should return a valid response
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)
    }

    fun `test tool call with project path`() = runBlocking {
        val arguments = buildJsonObject {
            put("project_path", project.basePath)
        }
        val request = buildToolCallRequest("list_run_configurations", arguments)
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should work with explicit project path
        assertNotNull(result["result"])
    }

    fun `test error handling for tools requiring paused session`() = runBlocking {
        // Tools like step_over, resume require a paused session
        val request = buildToolCallRequest("step_over", buildJsonObject {})
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should return error because no debug session is active
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)

        // The tool should return isError = true when no session is available
        val content = toolResult?.get("content")?.jsonArray
        assertNotNull(content)
    }

    fun `test set breakpoint with invalid file path`() = runBlocking {
        val arguments = buildJsonObject {
            put("file_path", "/nonexistent/path/file.kt")
            put("line", 10)
        }
        val request = buildToolCallRequest("set_breakpoint", arguments)
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should return error for invalid file
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)
        assertTrue(toolResult?.get("isError")?.jsonPrimitive?.boolean == true)
    }

    fun `test get variables without debug session`() = runBlocking {
        val request = buildToolCallRequest("get_variables", buildJsonObject {})
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should return error because no debug session
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)
        assertTrue(toolResult?.get("isError")?.jsonPrimitive?.boolean == true)
    }

    fun `test evaluate without debug session`() = runBlocking {
        val arguments = buildJsonObject {
            put("expression", "1 + 1")
        }
        val request = buildToolCallRequest("evaluate_expression", arguments)
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        // Should return error because no debug session
        val toolResult = result["result"]?.jsonObject
        assertNotNull(toolResult)
        assertTrue(toolResult?.get("isError")?.jsonPrimitive?.boolean == true)
    }

    fun `test multiple sequential requests`() = runBlocking {
        // Test that multiple requests can be processed sequentially
        val requests = listOf(
            buildJsonRpcRequest("ping", 1),
            buildJsonRpcRequest("tools/list", 2),
            buildJsonRpcRequest("ping", 3)
        )

        requests.forEachIndexed { index, request ->
            val response = handler.handleRequest(encodeRequest(request))
            val result = parseResponse(response)
            assertNull("Request $index should not have error", result["error"])
        }
    }

    fun `test server info values`() = runBlocking {
        val request = buildJsonRpcRequest("initialize", 1)
        val response = handler.handleRequest(encodeRequest(request))
        val result = parseResponse(response)

        val serverInfo = result["result"]?.jsonObject?.get("serverInfo")?.jsonObject
        assertNotNull(serverInfo)

        val name = serverInfo?.get("name")?.jsonPrimitive?.content
        val version = serverInfo?.get("version")?.jsonPrimitive?.content

        assertNotNull(name)
        assertNotNull(version)
        assertTrue("Server name should not be empty", name!!.isNotEmpty())
        assertTrue("Server version should not be empty", version!!.isNotEmpty())
    }

    private fun buildJsonRpcRequest(method: String, id: Int): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", buildJsonObject {})
        }
    }

    private fun buildToolCallRequest(toolName: String, arguments: JsonObject): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", toolName)
                put("arguments", arguments)
            }
        }
    }

    private fun encodeRequest(request: JsonObject): String {
        return request.toString()
    }

    private fun parseResponse(response: String): JsonObject {
        return json.parseToJsonElement(response).jsonObject
    }
}
