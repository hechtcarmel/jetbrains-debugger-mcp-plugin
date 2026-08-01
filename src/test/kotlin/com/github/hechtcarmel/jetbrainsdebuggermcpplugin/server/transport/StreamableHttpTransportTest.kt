package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the Streamable HTTP transport (MCP 2025-03-26) at `/debugger-mcp/streamable-http` as an
 * MCP client observes it.
 *
 * This is the primary transport — the one every install command in the README points at — and
 * before this file nothing exercised it over HTTP at all.
 */
class StreamableHttpTransportTest : McpHttpTestCase() {

    private val path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH

    // ── Handshake ───────────────────────────────────────────────────────────────────────

    fun `test initialize issues a session id and reports server identity`() {
        val response = post(
            path,
            rpc("initialize", params = """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        )

        assertEquals(200, response.statusCode())

        val sessionId = response.header(McpConstants.MCP_SESSION_ID_HEADER)
        assertNotNull("initialize must issue an ${McpConstants.MCP_SESSION_ID_HEADER} header", sessionId)
        assertTrue(
            "Session id should be dash-free hex, was: $sessionId",
            sessionId!!.matches(Regex("[0-9a-f]{32}"))
        )

        val result = response.jsonBody()["result"]!!.jsonObject
        assertEquals(
            "Streamable HTTP advertises the 2025-03-26 protocol",
            McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            result["protocolVersion"]!!.jsonPrimitive.content
        )

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals(McpConstants.getServerName(), serverInfo["name"]!!.jsonPrimitive.content)
        assertTrue(
            "serverInfo.version must not be blank",
            serverInfo["version"]!!.jsonPrimitive.content.isNotBlank()
        )

        assertNotNull(
            "Server must advertise the tools capability",
            result["capabilities"]!!.jsonObject["tools"]
        )
    }

    /**
     * The server currently echoes a fixed protocol version per transport and ignores what the
     * client asked for. Pinned because the MCP SDK negotiates instead — this test is the record
     * of what changes.
     */
    fun `test initialize ignores the client's requested protocol version`() {
        val response = post(
            path,
            rpc("initialize", params = """{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        )

        assertEquals(
            "Requested 2024-11-05 but the transport answers with its own fixed version",
            McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            response.jsonBody()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    // ── Session enforcement ─────────────────────────────────────────────────────────────

    fun `test requests without a session id are rejected`() {
        val response = post(path, rpc("tools/list"))

        assertEquals(400, response.statusCode())
        val error = response.jsonBody()["error"]!!.jsonObject
        assertEquals(-32600, error["code"]!!.jsonPrimitive.intOrNull)
        assertTrue(
            "Error should name the missing header, was: ${error["message"]}",
            error["message"]!!.jsonPrimitive.content.contains(McpConstants.MCP_SESSION_ID_HEADER)
        )
    }

    fun `test requests with an unknown session id are rejected`() {
        val response = post(path, rpc("tools/list"), sessionHeaders("00000000000000000000000000000000"))

        assertEquals(404, response.statusCode())
    }

    fun `test DELETE terminates the session so later requests fail`() {
        val sessionId = initializeStreamable()

        assertEquals(200, post(path, rpc("ping"), sessionHeaders(sessionId)).statusCode())
        assertEquals(200, delete(path, sessionHeaders(sessionId)).statusCode())
        assertEquals(
            "A deleted session must no longer be usable",
            404,
            post(path, rpc("ping"), sessionHeaders(sessionId)).statusCode()
        )
    }

    // ── Methods ─────────────────────────────────────────────────────────────────────────

    fun `test tools slash list returns the full advertised tool surface`() {
        val sessionId = initializeStreamable()
        val response = post(path, rpc("tools/list"), sessionHeaders(sessionId))

        assertEquals(200, response.statusCode())
        val tools = response.jsonBody()["result"]!!.jsonObject["tools"]!!.jsonArray

        assertEquals(
            "tools/list must expose exactly the registered tools",
            registry.getToolCount(),
            tools.size
        )
        tools.forEach { tool ->
            val obj = tool.jsonObject
            assertTrue("every tool needs a name", obj["name"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue("every tool needs a description", obj["description"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("object", obj["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    /**
     * `ToolDefinition.outputSchema` and `.annotations` are nullable, and the envelope serializer
     * sets `explicitNulls = false` (JsonRpcHandler.kt:21). Strict clients reject
     * `"outputSchema": null` as a schema-type violation, so their *absence* is the contract.
     */
    fun `test tools omit null outputSchema rather than emitting null`() {
        val sessionId = initializeStreamable()
        val tools = post(path, rpc("tools/list"), sessionHeaders(sessionId))
            .jsonBody()["result"]!!.jsonObject["tools"]!!.jsonArray

        assertTrue(
            "Most tools declare no outputSchema, so the omission case must actually occur",
            tools.any { "outputSchema" !in it.jsonObject }
        )

        val emittingNull = tools
            .map { it.jsonObject }
            .filter { it["outputSchema"] is JsonNull || it["annotations"] is JsonNull }
            .map { it["name"]!!.jsonPrimitive.content }
            .sorted()

        assertEquals(
            "Null optional fields must be omitted, not serialized as null — strict clients " +
                "reject \"outputSchema\": null as a schema-type violation.",
            emptyList<String>(),
            emittingNull
        )
    }

    fun `test ping returns an empty result`() {
        val sessionId = initializeStreamable()
        val response = post(path, rpc("ping"), sessionHeaders(sessionId))

        assertEquals(200, response.statusCode())
        assertEquals(0, response.jsonBody()["result"]!!.jsonObject.size)
    }

    fun `test unknown method returns method not found`() {
        val sessionId = initializeStreamable()
        val response = post(path, rpc("resources/list"), sessionHeaders(sessionId))

        val error = response.jsonBody()["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.intOrNull)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("resources/list"))
    }

    fun `test notifications are accepted without a response body`() {
        val sessionId = initializeStreamable()
        val response = post(path, rpc("notifications/initialized", id = null), sessionHeaders(sessionId))

        assertEquals("A JSON-RPC notification gets 202 and no body", 202, response.statusCode())
        assertTrue(response.body().isEmpty())
    }

    // ── Tool dispatch ───────────────────────────────────────────────────────────────────

    /**
     * `list_breakpoints` needs only a Project, so it exercises the whole success path — dispatch,
     * `createJsonResult`, `structuredContent` — end to end over HTTP.
     */
    fun `test tools slash call returns content and structuredContent for a JSON tool`() {
        val sessionId = initializeStreamable()
        val response = post(path, toolCall("list_breakpoints"), sessionHeaders(sessionId))

        assertEquals(200, response.statusCode())
        val result = response.jsonBody()["result"]!!.jsonObject

        assertFalse("list_breakpoints should succeed", result["isError"]!!.jsonPrimitive.booleanOrNull!!)

        val content = result["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)

        val structured = result["structuredContent"]!!.jsonObject
        assertTrue("structuredContent mirrors the payload", "breakpoints" in structured)
        assertEquals(
            "content[0].text must be the same JSON payload, stringified",
            structured,
            json.parseToJsonElement(content[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        )
    }

    /**
     * Tool failures are transported as a SUCCESSFUL JSON-RPC result carrying `isError: true`,
     * never as a JSON-RPC error object — so the model can read the message and act on it.
     */
    fun `test a failing tool is reported as isError not as a protocol error`() {
        val sessionId = initializeStreamable()
        val response = post(path, toolCall("get_variables"), sessionHeaders(sessionId))

        assertEquals(200, response.statusCode())
        val body = response.jsonBody()
        assertNull("Tool failure must not surface as a JSON-RPC error", body["error"])

        val result = body["result"]!!.jsonObject
        assertTrue("No debug session is active, so this must fail", result["isError"]!!.jsonPrimitive.booleanOrNull!!)
        assertTrue(
            "The failure message should explain there is no session, was: ${result["content"]}",
            result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("session", ignoreCase = true)
        )
    }

    /**
     * An unknown tool is reported through the JSON-RPC `error` channel today, with the handler's
     * two prefixes stacked. Pinned verbatim because the SDK reports unknown tools as
     * `isError: true` results instead — a client-visible change that must be deliberate.
     */
    fun `test unknown tool is reported as a protocol error with a doubled prefix`() {
        val sessionId = initializeStreamable()
        val response = post(path, toolCall("does_not_exist"), sessionHeaders(sessionId))

        val error = response.jsonBody()["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.intOrNull)
        assertEquals("Method not found: Tool not found: does_not_exist", error["message"]!!.jsonPrimitive.content)
    }

    // ── Malformed input ─────────────────────────────────────────────────────────────────

    fun `test empty body is a parse error with 400`() {
        val sessionId = initializeStreamable()
        val response = post(path, "", sessionHeaders(sessionId))

        assertEquals(400, response.statusCode())
        assertEquals(-32700, response.jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull)
    }

    fun `test malformed JSON is a parse error with 400`() {
        val sessionId = initializeStreamable()
        val response = post(path, "{not json", sessionHeaders(sessionId))

        assertEquals(400, response.statusCode())
        assertEquals(-32700, response.jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull)
    }

    fun `test a JSON object that is not a JSON-RPC message is rejected`() {
        val sessionId = initializeStreamable()
        val response = post(path, """{"hello":"world"}""", sessionHeaders(sessionId))

        assertEquals(400, response.statusCode())
        assertEquals(-32600, response.jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull)
    }

    fun `test empty batch is rejected`() {
        val sessionId = initializeStreamable()
        val response = post(path, "[]", sessionHeaders(sessionId))

        assertEquals(400, response.statusCode())
        assertTrue(
            response.jsonBody()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("must not be empty")
        )
    }

    fun `test initialize must not be batched`() {
        val response = post(path, """[${rpc("initialize", params = "{}")}]""")

        assertEquals(400, response.statusCode())
        assertTrue(
            response.jsonBody()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("must not be batched")
        )
    }

    // ── Method routing ──────────────────────────────────────────────────────────────────

    fun `test GET is not allowed and advertises the permitted methods`() {
        val response = get(path)

        assertEquals(405, response.statusCode())
        assertEquals("POST, DELETE", response.header("Allow"))
    }
}
