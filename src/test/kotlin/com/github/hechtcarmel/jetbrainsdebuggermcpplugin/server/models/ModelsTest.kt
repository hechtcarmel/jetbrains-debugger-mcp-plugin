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

    // Helper Function Tests

    @Test
    fun `textContent creates Text ContentBlock`() {
        val content = textContent("Test message")

        assertTrue(content is ContentBlock.Text)
        assertEquals("Test message", (content as ContentBlock.Text).text)
    }

    @Test
    fun `successResult creates non-error ToolCallResult`() {
        val result = successResult("Operation successful")

        assertFalse(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("Operation successful", (result.content[0] as ContentBlock.Text).text)
    }

    @Test
    fun `errorResult creates error ToolCallResult`() {
        val result = errorResult("Something went wrong")

        assertTrue(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("Something went wrong", (result.content[0] as ContentBlock.Text).text)
    }

    @Test
    fun `successResult with empty string`() {
        val result = successResult("")

        assertFalse(result.isError)
        assertEquals("", (result.content[0] as ContentBlock.Text).text)
    }

    @Test
    fun `errorResult with empty string`() {
        val result = errorResult("")

        assertTrue(result.isError)
        assertEquals("", (result.content[0] as ContentBlock.Text).text)
    }

    // ToolDefinition with Annotations Tests

    @Test
    fun `ToolDefinition without annotations serializes correctly`() {
        val definition = ToolDefinition(
            name = "simple_tool",
            description = "A simple tool",
            inputSchema = buildJsonObject { put("type", "object") },
            annotations = null
        )

        val encoded = json.encodeToString(definition)

        assertTrue(encoded.contains("\"name\":\"simple_tool\""))
        assertTrue(encoded.contains("\"description\":\"A simple tool\""))
        assertTrue(encoded.contains("\"inputSchema\""))
    }

    @Test
    fun `ToolDefinition with annotations serializes correctly`() {
        val definition = ToolDefinition(
            name = "annotated_tool",
            description = "A tool with annotations",
            inputSchema = buildJsonObject { put("type", "object") },
            annotations = ToolAnnotations.readOnly("Annotated Tool")
        )

        val encoded = json.encodeToString(definition)

        assertTrue(encoded.contains("\"annotations\""))
        assertTrue(encoded.contains("\"title\":\"Annotated Tool\""))
    }

    // Additional Edge Cases

    @Test
    fun `JsonRpcRequest with null id`() {
        val request = JsonRpcRequest(
            id = null,
            method = "notification",
            params = null
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"method\":\"notification\""))
    }

    @Test
    fun `JsonRpcRequest with string id`() {
        val request = JsonRpcRequest(
            id = JsonPrimitive("abc-123"),
            method = "test",
            params = null
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"id\":\"abc-123\""))
    }

    @Test
    fun `JsonRpcError with data field`() {
        val error = JsonRpcError(
            code = JsonRpcErrorCodes.INVALID_PARAMS,
            message = "Missing required field",
            data = buildJsonObject { put("field", "name") }
        )

        val encoded = json.encodeToString(error)

        assertTrue(encoded.contains("\"data\""))
        assertTrue(encoded.contains("\"field\":\"name\""))
    }

    @Test
    fun `ServerCapabilities with default values`() {
        val capabilities = ServerCapabilities()

        assertNotNull(capabilities.tools)
        assertEquals(false, capabilities.tools?.listChanged)
    }

    @Test
    fun `ToolCapability default listChanged is false`() {
        val capability = ToolCapability()

        assertEquals(false, capability.listChanged)
    }

    @Test
    fun `ServerInfo with null description`() {
        val serverInfo = ServerInfo(
            name = "server",
            version = "1.0.0",
            description = null
        )

        val encoded = json.encodeToString(serverInfo)

        assertTrue(encoded.contains("\"name\":\"server\""))
        assertTrue(encoded.contains("\"version\":\"1.0.0\""))
    }

    @Test
    fun `ToolCallParams with null arguments`() {
        val params = ToolCallParams(
            name = "tool_name",
            arguments = null
        )

        val encoded = json.encodeToString(params)

        assertTrue(encoded.contains("\"name\":\"tool_name\""))
    }

    @Test
    fun `ToolCallResult with multiple content blocks`() {
        val result = ToolCallResult(
            content = listOf(
                ContentBlock.Text(text = "First line"),
                ContentBlock.Text(text = "Second line")
            ),
            isError = false
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("First line"))
        assertTrue(encoded.contains("Second line"))
    }

    @Test
    fun `ToolCallResult with empty content list`() {
        val result = ToolCallResult(
            content = emptyList(),
            isError = false
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"content\":[]"))
    }

    @Test
    fun `ToolCallResult with structuredContent serialization`() {
        val structuredData = buildJsonObject {
            put("sessionId", "12345")
            put("frameIndex", 0)
        }
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text(text = """{"sessionId":"12345","frameIndex":0}""")),
            isError = false,
            structuredContent = structuredData
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"structuredContent\""))
        assertTrue(encoded.contains("\"sessionId\":\"12345\""))
        assertTrue(encoded.contains("\"frameIndex\":0"))
    }

    @Test
    fun `ToolCallResult without structuredContent omits field when null`() {
        val jsonWithExplicitNulls = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text(text = "simple result")),
            isError = false,
            structuredContent = null
        )

        val encoded = jsonWithExplicitNulls.encodeToString(result)

        assertFalse(encoded.contains("\"structuredContent\""))
    }
}
