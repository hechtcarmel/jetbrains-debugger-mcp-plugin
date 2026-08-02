package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Pins the two transports that predate Streamable HTTP and are still advertised to users:
 *
 * - **Stateless HTTP** — `POST /debugger-mcp` with no `sessionId`, an immediate JSON reply.
 * - **Legacy SSE (2024-11-05)** — `GET /debugger-mcp/sse` opens a stream and pushes an
 *   `event: endpoint` naming the URL to POST to; replies come back out-of-band on the stream.
 *
 * Both are reachable from shipped client configurations (`ClientConfigGenerator.getLegacySseUrl`),
 * so silently dropping or reshaping either during the SDK migration breaks working setups. The
 * SDK's own SSE support uses a different endpoint-event payload and session-id format, which is
 * exactly the kind of change this file makes visible.
 */
class LegacyTransportTest : McpHttpTestCase() {

    // ── Stateless HTTP ──────────────────────────────────────────────────────────────────

    fun `test stateless POST answers immediately without any session`() {
        val response = post(McpConstants.MCP_ENDPOINT_PATH, rpc("tools/list"))

        assertEquals(200, response.statusCode())
        assertEquals(
            "Stateless HTTP must not require a session handshake",
            registry.getToolCount(),
            response.jsonBody()["result"]!!.jsonObject["tools"]!!.jsonArray.size
        )
    }

    /**
     * The version is *negotiated* rather than hardcoded per transport: the server echoes back
     * the version the client asked for, provided it supports it. Before the SDK migration every
     * request to this endpoint got a fixed `2024-11-05` no matter what the client requested.
     */
    fun `test stateless initialize negotiates the requested protocol version`() {
        // 2025-03-26 is deliberately NOT what this endpoint used to answer (it hardcoded
        // 2024-11-05), so an echo here proves negotiation rather than a lucky constant.
        val response = post(McpConstants.MCP_ENDPOINT_PATH, initializeRequest("2025-03-26"))

        assertEquals(
            "2025-03-26",
            response.jsonBody()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    /**
     * The server's prose description moved from a non-standard `serverInfo.description` field to
     * `instructions`, which is where the MCP specification actually carries it.
     */
    fun `test initialize carries the server description as instructions`() {
        val result = post(McpConstants.MCP_ENDPOINT_PATH, initializeRequest("2024-11-05"))
            .jsonBody()["result"]!!.jsonObject

        assertNull("description was never a spec field", result["serverInfo"]!!.jsonObject["description"])
        assertTrue(
            "instructions should carry the server description",
            result["instructions"]!!.jsonPrimitive.content.contains("Debug applications running in JetBrains IDEs")
        )
    }

    fun `test stateless POST issues no session header`() {
        val response = post(McpConstants.MCP_ENDPOINT_PATH, rpc("initialize", params = "{}"))

        assertNull(
            "Only the streamable transport is session-oriented",
            response.header(McpConstants.MCP_SESSION_ID_HEADER)
        )
    }

    /**
     * An empty body is now a **400** on this endpoint as well. It used to answer 200 here and 400
     * on the streamable path — an asymmetry that existed only because two hand-written handlers
     * disagreed. Both paths now run the same SDK transport, so they agree.
     */
    fun `test stateless empty body is a parse error with 400`() {
        val response = post(McpConstants.MCP_ENDPOINT_PATH, "")

        assertEquals(400, response.statusCode())
        assertEquals(-32700, response.jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull)
    }

    fun `test stateless tool call succeeds end to end`() {
        val response = post(McpConstants.MCP_ENDPOINT_PATH, toolCall("list_debug_sessions"))

        val result = response.jsonBody()["result"]!!.jsonObject
        assertFalse("list_debug_sessions needs no debug session", result["isError"]!!.jsonPrimitive.booleanOrNull!!)
        assertTrue("sessions" in result["structuredContent"]!!.jsonObject)
    }

    fun `test POST to an unknown SSE session is rejected`() {
        val response = post("${McpConstants.MCP_ENDPOINT_PATH}?sessionId=does-not-exist", rpc("ping"))

        assertEquals(404, response.statusCode())
        assertTrue(
            "The unknown-session reply is plain text, not JSON",
            response.body().contains("Session not found")
        )
    }

    // ── Legacy SSE ──────────────────────────────────────────────────────────────────────

    /**
     * Opens the SSE stream, reads the `endpoint` event, POSTs a request to the URL it names and
     * asserts the reply arrives back on the stream. This is the whole legacy round trip.
     */
    fun `test SSE announces an endpoint and delivers replies on the stream`() {
        // A dedicated client: an SSE stream never completes, so this one is torn down with
        // shutdownNow() rather than close(), which would block forever waiting for it.
        val client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val streamRequest = HttpRequest.newBuilder(URI.create(url(McpConstants.SSE_ENDPOINT_PATH)))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val stream = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream())
        val body = stream.body()
        try {
            assertEquals(200, stream.statusCode())
            assertTrue(
                "SSE must be served as text/event-stream, was: ${stream.headers().firstValue("Content-Type")}",
                stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")
            )

            val lines = java.util.concurrent.LinkedBlockingQueue<String>()
            val reader = Thread {
                runCatching { body.bufferedReader().forEachLine { lines.put(it) } }
            }
            reader.isDaemon = true
            reader.start()

            val endpointUrl = readEndpointEvent(lines)
            assertTrue(
                "The endpoint event must point at the POST path with a sessionId, was: $endpointUrl",
                endpointUrl.startsWith("${McpConstants.MCP_ENDPOINT_PATH}?${McpConstants.SESSION_ID_PARAM}=")
            )

            val postResponse = post(endpointUrl, rpc("tools/list"))
            assertEquals(
                "The SSE transport acknowledges with 202 and replies out of band",
                202,
                postResponse.statusCode()
            )

            val message = readMessageEvent(lines)
            val tools = json.parseToJsonElement(message).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(
                "The reply delivered over SSE must carry the same tool surface",
                registry.getToolCount(),
                tools.size
            )
        } finally {
            runCatching { body.close() }
            client.shutdownNow()
        }
    }

    private fun readEndpointEvent(lines: java.util.concurrent.BlockingQueue<String>): String =
        readEventData(lines, expectedEvent = "endpoint")

    private fun readMessageEvent(lines: java.util.concurrent.BlockingQueue<String>): String =
        readEventData(lines, expectedEvent = "message")

    /**
     * Reads SSE frames until the named event's `data:` line arrives.
     *
     * Fails rather than hanging if it never does — a silent timeout here would make the whole
     * round trip vacuous.
     */
    private fun readEventData(lines: java.util.concurrent.BlockingQueue<String>, expectedEvent: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var sawEvent = false
        while (System.nanoTime() < deadline) {
            val line = lines.poll(1, TimeUnit.SECONDS) ?: continue
            when {
                line.startsWith("event:") -> sawEvent = line.removePrefix("event:").trim() == expectedEvent
                line.startsWith("data:") && sawEvent -> return line.removePrefix("data:").trim()
            }
        }
        throw AssertionError("Timed out waiting for SSE '$expectedEvent' event")
    }

    /**
     * `initialize` params are validated by the SDK now, so they have to be complete — an empty
     * object is rejected rather than silently accepted.
     */
    private fun initializeRequest(protocolVersion: String): String = rpc(
        "initialize",
        params = """{"protocolVersion":"$protocolVersion","capabilities":{},"clientInfo":{"name":"t","version":"1"}}"""
    )

    /**
     * The stateless endpoint mints one SDK ServerSession per request against the single shared
     * Server. Server.createSession only deregisters a session when it *closes*, and the transport
     * only closes explicitly — so without the transport.close() in KtorMcpServer's stateless
     * handler, every request here would leak a session (plus its notification subscription) for
     * the life of the IDE. This pins the release.
     */
    fun `test stateless requests do not accumulate sessions on the shared server`() {
        repeat(5) { post(McpConstants.MCP_ENDPOINT_PATH, rpc("tools/list")) }

        // The release runs in the request coroutine *after* the response is written, so give it a
        // bounded moment rather than racing it. What is pinned is that sessions do not
        // accumulate — not that the close beats the HTTP client back.
        val deadline = System.currentTimeMillis() + 5_000
        while (mcpServer.sessions.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals(
            "stateless sessions must be closed after each request",
            emptySet<Any>(),
            mcpServer.sessions.keys
        )
    }

    /**
     * `Accept` and `Content-Type` stay advisory on the POST endpoints, as they always were. The
     * SDK transports would reject curl's defaults (the wildcard Accept is a substring miss for
     * its check; the implicit form-urlencoded Content-Type is a 415) — `withLenientMcpHeaders`
     * normalises both before the SDK sees the request. This plugin's issue history is one
     * connection bug after another; a header the server never used to read must not start
     * rejecting clients that worked yesterday.
     */
    fun `test curl-style headers are accepted on the stateless endpoint`() {
        val wildcardAccept = post(McpConstants.MCP_ENDPOINT_PATH, rpc("tools/list"), mapOf("Accept" to "*/*"))
        assertEquals("Accept: */* must not be rejected", 200, wildcardAccept.statusCode())
        assertTrue("tools" in wildcardAccept.jsonBody()["result"]!!.jsonObject)

        val plainText = post(McpConstants.MCP_ENDPOINT_PATH, rpc("tools/list"), mapOf("Content-Type" to "text/plain"))
        assertEquals("a non-JSON Content-Type must not be rejected", 200, plainText.statusCode())
    }
}
