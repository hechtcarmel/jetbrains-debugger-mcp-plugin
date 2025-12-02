package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `JsonRpcRequest serialization round trip`() {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "test/method",
            params = buildJsonObject {
                put("key", "value")
            }
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<JsonRpcRequest>(encoded)

        assertEquals(request.method, decoded.method)
        assertEquals(request.jsonrpc, decoded.jsonrpc)
    }

    @Test
    fun `JsonRpcResponse with result serialization`() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(1),
            result = buildJsonObject {
                put("status", "ok")
            }
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"result\""))
        assertTrue(encoded.contains("\"status\""))
        assertFalse(encoded.contains("\"error\""))
    }

    @Test
    fun `JsonRpcResponse with error serialization`() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(1),
            error = JsonRpcError(
                code = JsonRpcErrorCodes.INTERNAL_ERROR,
                message = "Something went wrong"
            )
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"error\""))
        assertTrue(encoded.contains("-32603"))
        assertTrue(encoded.contains("Something went wrong"))
    }

    @Test
    fun `JsonRpcError codes are correct`() {
        assertEquals(-32700, JsonRpcErrorCodes.PARSE_ERROR)
        assertEquals(-32600, JsonRpcErrorCodes.INVALID_REQUEST)
        assertEquals(-32601, JsonRpcErrorCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcErrorCodes.INVALID_PARAMS)
        assertEquals(-32603, JsonRpcErrorCodes.INTERNAL_ERROR)
    }

    @Test
    fun `ToolCallResult with text content serialization`() {
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text(text = "Hello World")),
            isError = false
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("Hello World"))
        assertTrue(encoded.contains("\"text\""))
        assertFalse(encoded.contains("\"isError\":true"))
    }

    @Test
    fun `ToolCallResult with error serialization`() {
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text(text = "Error occurred")),
            isError = true
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"isError\":true"))
    }

    @Test
    fun `ToolDefinition serialization`() {
        val definition = ToolDefinition(
            name = "my_tool",
            description = "A test tool",
            inputSchema = buildJsonObject {
                put("type", "object")
            }
        )

        val encoded = json.encodeToString(definition)

        assertTrue(encoded.contains("\"name\":\"my_tool\""))
        assertTrue(encoded.contains("\"description\":\"A test tool\""))
        assertTrue(encoded.contains("\"inputSchema\""))
    }

    @Test
    fun `ServerInfo serialization`() {
        val serverInfo = ServerInfo(
            name = "test-server",
            version = "1.0.0",
            description = "Test description"
        )

        val encoded = json.encodeToString(serverInfo)

        assertTrue(encoded.contains("test-server"))
        assertTrue(encoded.contains("1.0.0"))
    }

    @Test
    fun `InitializeResult serialization`() {
        val result = InitializeResult(
            protocolVersion = "2024-11-05",
            serverInfo = ServerInfo(
                name = "mcp-server",
                version = "1.0.0"
            ),
            capabilities = ServerCapabilities(
                tools = ToolCapability(listChanged = true)
            )
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"protocolVersion\":\"2024-11-05\""))
        assertTrue(encoded.contains("\"capabilities\""))
        assertTrue(encoded.contains("\"tools\""))
    }

    @Test
    fun `ToolsListResult serialization`() {
        val result = ToolsListResult(
            tools = listOf(
                ToolDefinition(
                    name = "tool1",
                    description = "First tool",
                    inputSchema = buildJsonObject { put("type", "object") }
                ),
                ToolDefinition(
                    name = "tool2",
                    description = "Second tool",
                    inputSchema = buildJsonObject { put("type", "object") }
                )
            )
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("tool1"))
        assertTrue(encoded.contains("tool2"))
        assertTrue(encoded.contains("\"tools\""))
    }

    @Test
    fun `ContentBlock Text type discriminator`() {
        // When serialized as part of a container using polymorphic serialization,
        // the type discriminator is included. Direct encoding omits it.
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text(text = "Sample text")),
            isError = false
        )
        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"text\":\"Sample text\""))
    }
}
