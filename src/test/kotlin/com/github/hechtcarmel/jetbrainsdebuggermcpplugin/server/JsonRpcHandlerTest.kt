package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class JsonRpcHandlerTest : BasePlatformTestCase() {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var handler: JsonRpcHandler

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        handler = JsonRpcHandler(toolRegistry)
    }

    fun `test parse error for invalid json`() = runBlocking {
        val response = handler.handleRequest("not valid json")
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNotNull(jsonResponse["error"])
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, jsonResponse["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
    }

    fun `test parse error for incomplete json`() = runBlocking {
        val response = handler.handleRequest("{\"jsonrpc\": \"2.0\"")
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNotNull(jsonResponse["error"])
    }

    fun `test initialize returns server info`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNull(jsonResponse["error"])
        assertNotNull(jsonResponse["result"])

        val result = jsonResponse["result"]?.jsonObject
        assertEquals(McpConstants.MCP_PROTOCOL_VERSION, result?.get("protocolVersion")?.jsonPrimitive?.content)

        val serverInfo = result?.get("serverInfo")?.jsonObject
        assertEquals(McpConstants.SERVER_NAME, serverInfo?.get("name")?.jsonPrimitive?.content)
        assertEquals(McpConstants.SERVER_VERSION, serverInfo?.get("version")?.jsonPrimitive?.content)
    }

    fun `test initialized returns response with id`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "notifications/initialized",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        // Should have id in response and be valid JSON-RPC
        assertEquals(2, jsonResponse["id"]?.jsonPrimitive?.int)
        assertEquals("2.0", jsonResponse["jsonrpc"]?.jsonPrimitive?.content)
    }

    fun `test tools list returns registered tools`() = runBlocking {
        toolRegistry.register(createMockTool("tool_one", "First tool"))
        toolRegistry.register(createMockTool("tool_two", "Second tool"))

        val request = """
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/list",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNull(jsonResponse["error"])
        val result = jsonResponse["result"]?.jsonObject
        val tools = result?.get("tools")?.jsonArray

        assertEquals(2, tools?.size)
    }

    fun `test tools list returns empty array when no tools`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 4,
                "method": "tools/list",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        val result = jsonResponse["result"]?.jsonObject
        val tools = result?.get("tools")?.jsonArray

        assertEquals(0, tools?.size)
    }

    fun `test tools call with missing params returns error`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 5,
                "method": "tools/call"
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNotNull(jsonResponse["error"])
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, jsonResponse["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
    }

    fun `test tools call with missing tool name returns error`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 6,
                "method": "tools/call",
                "params": {
                    "arguments": {}
                }
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNotNull(jsonResponse["error"])
        val message = jsonResponse["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        assertTrue(message?.contains("Missing tool name") == true)
    }

    fun `test tools call with unknown tool returns error`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 7,
                "method": "tools/call",
                "params": {
                    "name": "nonexistent_tool",
                    "arguments": {}
                }
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNotNull(jsonResponse["error"])
        val message = jsonResponse["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        assertTrue(message?.contains("Tool not found") == true)
    }

    fun `test method not found for unknown method`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 8,
                "method": "unknown/method",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNotNull(jsonResponse["error"])
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, jsonResponse["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
    }

    fun `test ping returns empty result`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 9,
                "method": "ping",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertNull(jsonResponse["error"])
        assertEquals(9, jsonResponse["id"]?.jsonPrimitive?.int)
    }

    fun `test response includes request id`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": "test-id-123",
                "method": "initialize",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject

        assertEquals("test-id-123", jsonResponse["id"]?.jsonPrimitive?.content)
    }

    fun `test initialize returns capabilities with tools`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 10,
                "method": "initialize",
                "params": {}
            }
        """.trimIndent()

        val response = handler.handleRequest(request)
        val jsonResponse = Json.parseToJsonElement(response).jsonObject
        val result = jsonResponse["result"]?.jsonObject

        val capabilities = result?.get("capabilities")?.jsonObject
        assertNotNull(capabilities?.get("tools"))
    }

    private fun createMockTool(name: String, description: String): McpTool {
        return object : McpTool {
            override val name: String = name
            override val description: String = description
            override val inputSchema: JsonObject = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }

            override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
                return ToolCallResult(
                    content = listOf(ContentBlock.Text(text = "Test result")),
                    isError = false
                )
            }
        }
    }
}
