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
     * The stateless endpoint reports the 2024-11-05 protocol while the streamable endpoint
     * reports 2025-03-26 — the version is a per-transport constant, not a negotiation.
     */
    fun `test stateless initialize reports the legacy protocol version`() {
        val response = post(McpConstants.MCP_ENDPOINT_PATH, rpc("initialize", params = "{}"))

        assertEquals(
            McpConstants.LEGACY_MCP_PROTOCOL_VERSION,
            response.jsonBody()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
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
     * An empty body returns **200** here but **400** on the streamable path. The asymmetry is
     * pinned deliberately: it is the kind of detail a rewrite silently normalises, and doing so
     * changes what existing clients see.
     */
    fun `test stateless empty body is a parse error with 200 unlike the streamable path`() {
        val response = post(McpConstants.MCP_ENDPOINT_PATH, "")

        assertEquals("The legacy endpoint answers 200 even for a parse error", 200, response.statusCode())
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
}
