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
            "the handshake echoes back the version the client asked for",
            "2025-03-26",
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
     * The protocol version is negotiated, not fixed per transport: a client that asks for a
     * version the server supports is answered in that version. Previously this endpoint replied
     * `2025-03-26` regardless of the request.
     */
    fun `test initialize negotiates the client's requested protocol version`() {
        val response = post(
            path,
            rpc("initialize", params = """{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        )

        assertEquals(
            "2024-11-05",
            response.jsonBody()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    // ── Session enforcement ─────────────────────────────────────────────────────────────

    /**
     * A non-initialize request with no session is still refused, but the refusal now comes from
     * the SDK's session state machine ("not initialized") rather than from a hand-written check
     * for a missing header. The status stays 400; the code and message changed.
     */
    fun `test requests without a session id are rejected`() {
        val response = post(path, rpc("tools/list"))

        assertEquals(400, response.statusCode())
        val error = response.jsonBody()["error"]!!.jsonObject
        assertEquals(-32000, error["code"]!!.jsonPrimitive.intOrNull)
        assertTrue(
            "Error should say the session was never initialized, was: ${error["message"]}",
            error["message"]!!.jsonPrimitive.content.contains("not initialized")
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
     * sets `explicitNulls = false` (the SDK's `McpJson` does the same). Strict clients reject
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

        assertEquals("A JSON-RPC notification gets 202", 202, response.statusCode())
        // The SDK answers a notification with a serialized JSON `null` (the old server sent an
        // empty body). Pinned exactly: if this changes again, clients parsing the body notice.
        assertEquals("null", response.body())
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
     * An unknown tool is now an `isError: true` **result**, not a JSON-RPC protocol error. That is
     * what the plugin's documented error contract always said should happen, and it is what a
     * model can actually read and act on — a transport-level error is opaque to it.
     *
     * It also drops the doubled "Method not found: Tool not found:" prefix the old router produced.
     */
    fun `test unknown tool is reported as an isError result, not a protocol error`() {
        val sessionId = initializeStreamable()
        val response = post(path, toolCall("does_not_exist"), sessionHeaders(sessionId))

        assertEquals(200, response.statusCode())
        val body = response.jsonBody()
        assertNull("must not be a protocol error", body["error"])

        val result = body["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(
            "the message should name the tool, was: ${result["content"]}",
            result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("does_not_exist")
        )
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

    /** Classified as a parse error (-32700) now rather than an invalid request (-32600). */
    fun `test a JSON object that is not a JSON-RPC message is rejected`() {
        val sessionId = initializeStreamable()
        val response = post(path, """{"hello":"world"}""", sessionHeaders(sessionId))

        assertEquals(400, response.statusCode())
        assertEquals(-32700, response.jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull)
    }

    /** A batch of requests is answered with a JSON array of results, one per request, in order. */
    fun `test a batch of requests is answered as an array`() {
        val sessionId = initializeStreamable()
        val response = post(path, "[${rpc("ping", id = 1)},${rpc("ping", id = 2)}]", sessionHeaders(sessionId))

        assertEquals(200, response.statusCode())
        val results = json.parseToJsonElement(response.body()).jsonArray
        assertEquals(2, results.size)
        assertEquals(1, results[0].jsonObject["id"]!!.jsonPrimitive.intOrNull)
        assertEquals(2, results[1].jsonObject["id"]!!.jsonPrimitive.intOrNull)
        results.forEach { assertEquals(0, it.jsonObject["result"]!!.jsonObject.size) }
    }

    /**
     * An empty batch is a batch containing no requests, so there is nothing to answer: 202. The
     * old hand-written classifier rejected it with a bespoke 400 message that no specification
     * asked for.
     */
    fun `test empty batch is accepted with nothing to answer`() {
        val sessionId = initializeStreamable()
        val response = post(path, "[]", sessionHeaders(sessionId))

        assertEquals(202, response.statusCode())
    }

    /**
     * Batching `initialize` is no longer refused outright. The old server rejected it with a
     * hand-written rule; the SDK simply processes the batch, so a well-formed batched initialize
     * succeeds and a malformed one fails on its params like any other request.
     */
    fun `test a batched initialize is processed rather than refused`() {
        val response = post(path, """[${rpc("initialize", params = "{}")}]""")

        assertEquals(200, response.statusCode())
        assertNotNull(
            "a batched initialize with empty params fails on its params, not on being batched",
            response.jsonBody()["error"]
        )
    }

    // ── Method routing ──────────────────────────────────────────────────────────────────

    /**
     * GET now opens the server -> client SSE channel instead of returning 405. This is the
     * capability the hand-rolled transport never had: notifications, progress and cancellation all
     * travel on this stream.
     */
    fun `test GET opens a server-to-client event stream`() {
        val response = openEventStream(path, sessionHeaders(initializeStreamable()))
        try {
            assertEquals(200, response.statusCode())
            assertTrue(
                "should be an event stream, was: ${response.header("Content-Type")}",
                response.header("Content-Type").orEmpty().startsWith("text/event-stream")
            )
        } finally {
            response.body().close()
        }
    }
}
