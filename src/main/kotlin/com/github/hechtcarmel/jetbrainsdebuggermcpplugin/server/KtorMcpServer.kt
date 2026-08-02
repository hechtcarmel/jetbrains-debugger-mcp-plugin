package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin's HTTP edge.
 *
 * Routing, paths and the Origin guard stay here; everything past them — JSON-RPC framing,
 * `initialize`, negotiation, session lifecycle, batching — belongs to the MCP SDK transports this
 * class hands each call to.
 *
 * The SDK ships its own Ktor route DSL (`Application.mcp`, `mcpStreamableHttp`). It is not used,
 * for two reasons: it hardcodes its own paths, and every published client config in the wild points
 * at *these* paths; and it leaves nowhere to mount [McpOriginGuard].
 *
 * Three transports are served, all of which real clients depend on:
 *
 *  1. **Streamable HTTP** (MCP 2025-03-26 and later) — `POST`/`GET`/`DELETE
 *     /debugger-mcp/streamable-http`, session in the `Mcp-Session-Id` header.
 *  2. **Legacy HTTP+SSE** (MCP 2024-11-05) — `GET /debugger-mcp/sse` for the stream, with replies
 *     posted back to `/debugger-mcp?sessionId=…`.
 *  3. **Stateless HTTP** — `POST /debugger-mcp` with no session at all. Not an MCP transport; a
 *     plugin convenience that predates Streamable HTTP and that scripts and tests rely on.
 */
class KtorMcpServer(
    private val port: Int,
    private val host: String = McpConstants.DEFAULT_SERVER_HOST,
    private val mcpServer: Server,
) : Disposable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Live Streamable HTTP transports, keyed by the session id the SDK minted for each. */
    private val streamableTransports = ConcurrentHashMap<String, StreamableHttpServerTransport>()

    /** Live legacy-SSE transports, keyed by the `sessionId` query parameter. */
    private val sseTransports = ConcurrentHashMap<String, SseServerTransport>()

    companion object {
        private val LOG = logger<KtorMcpServer>()
    }

    sealed class StartResult {
        data object Success : StartResult()
        data class PortInUse(val port: Int) : StartResult()
        data class Error(val message: String, val cause: Throwable? = null) : StartResult()
    }

    fun start(): StartResult {
        return try {
            server = embeddedServer(CIO, port = port, host = host) { configure() }
            server?.start(wait = false)
            LOG.info("MCP Server started on http://$host:$port")
            StartResult.Success
        } catch (e: BindException) {
            LOG.warn("Port $port is already in use", e)
            StartResult.PortInUse(port)
        } catch (e: Exception) {
            // CIO reports a failed bind by cancelling the start coroutine, so the BindException we
            // actually care about arrives wrapped.
            if (e is CancellationException) {
                val cause = e.cause
                if (cause is BindException) {
                    LOG.warn("Failed to start server on $host:$port: ${cause.message}", cause)
                    return StartResult.Error("Failed to bind to $host:$port. ${cause.message}", cause)
                }
                throw e
            }
            LOG.error("Failed to start MCP server", e)
            StartResult.Error(e.message ?: "Unknown error", e)
        }
    }

    fun stop() {
        try {
            server?.stop(1000, 2000)
            server = null
            // Closing each transport cascades into the SDK deregistering its ServerSession.
            // Dropping the maps alone would strand those sessions in the shared Server across
            // every settings-driven restart.
            runBlocking {
                withTimeoutOrNull(2_000) {
                    streamableTransports.values.forEach { runCatching { it.close() } }
                    sseTransports.values.forEach { runCatching { it.close() } }
                }
            }
            streamableTransports.clear()
            sseTransports.clear()
            LOG.info("MCP Server stopped")
        } catch (e: Exception) {
            LOG.warn("Error stopping MCP server", e)
        }
    }

    fun isRunning(): Boolean = server != null

    override fun dispose() = stop()

    // ======================== Routing ========================

    private fun Application.configure() {
        // Required by both SDK transports; they stream over Ktor's SSE plugin.
        install(SSE)
        // Mandatory at SDK 0.10.0: the transports answer with @Serializable envelopes, and Ktor
        // turns an untransformable response body into a bare 406, so *every* response fails
        // without this. Later SDKs install it themselves.
        install(ContentNegotiation) { json(McpJson) }

        routing {
            route(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH) {
                McpOriginGuard.install(this)
                options { McpOriginGuard.respondToPreflight(call) }

                post { handleStreamablePost() }
                delete { requireStreamableTransport(call)?.handleRequest(null, call) }
                // Opens the server -> client notification channel. Before the SDK migration this
                // was a flat 405, which meant no notifications, no progress and no cancellation.
                sse {
                    val transport = requireStreamableTransport(call) ?: return@sse
                    transport.handleRequest(this, call)
                }
            }

            route(McpConstants.SSE_ENDPOINT_PATH) {
                McpOriginGuard.install(this)
                options { McpOriginGuard.respondToPreflight(call) }

                sse { serveLegacySseStream() }
                // Historical alias: some clients POST back to the stream path rather than the
                // endpoint they were handed. Kept because removing it would break them silently.
                post { handleLegacyOrStatelessPost() }
            }

            route(McpConstants.MCP_ENDPOINT_PATH) {
                McpOriginGuard.install(this)
                options { McpOriginGuard.respondToPreflight(call) }

                post { handleLegacyOrStatelessPost() }
            }
        }
    }

    // ======================== Streamable HTTP ========================

    private suspend fun RoutingContext.handleStreamablePost() {
        val call = call.withLenientMcpHeaders()
        val sessionId = call.request.header(McpConstants.MCP_SESSION_ID_HEADER)
        if (sessionId != null) {
            requireStreamableTransport(call)?.handleRequest(null, call)
            return
        }

        // No session yet: this is an `initialize`. Mint a transport, let the SDK negotiate, and
        // register it under whichever id the SDK settles on.
        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
        )
        // Preserve the dash-free 32-hex session id format clients have seen since 4.x.
        transport.setSessionIdGenerator { UUID.randomUUID().toString().replace("-", "") }
        transport.setOnSessionInitialized { streamableTransports[it] = transport }
        transport.setOnSessionClosed { streamableTransports.remove(it) }

        mcpServer.createSession(transport)
        transport.handleRequest(null, call)

        // If the handshake did not complete (the request wasn't a valid initialize), nothing
        // registered this transport — close it, or its ServerSession stays in the shared SDK
        // Server's registry forever. A successful initialize sets sessionId and registers the
        // transport, so this never closes a live session.
        if (transport.sessionId == null) {
            transport.close()
        }
    }

    /**
     * Resolves the transport for the request's `Mcp-Session-Id`, or rejects the call.
     *
     * Rejecting (rather than silently returning) matters most on the SSE route: without it an
     * unknown session would receive an empty 200 event stream and hang waiting for events that
     * can never arrive.
     */
    private suspend fun requireStreamableTransport(call: ApplicationCall): StreamableHttpServerTransport? {
        val sessionId = call.request.header(McpConstants.MCP_SESSION_ID_HEADER)
        if (sessionId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing ${McpConstants.MCP_SESSION_ID_HEADER} header")
            return null
        }
        val transport = streamableTransports[sessionId]
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found or expired")
            return null
        }
        return transport
    }

    // ======================== Legacy SSE + stateless ========================

    private suspend fun io.ktor.server.sse.ServerSSESession.serveLegacySseStream() {
        // The endpoint handed to the client is the plugin's own POST path, so the `endpoint` event
        // reads `/debugger-mcp?sessionId=…` exactly as it always has.
        val transport = SseServerTransport(McpConstants.MCP_ENDPOINT_PATH, this)
        sseTransports[transport.sessionId] = transport

        mcpServer.createSession(transport)
        LOG.info("Legacy SSE session established: ${transport.sessionId}")

        try {
            awaitCancellation()
        } finally {
            sseTransports.remove(transport.sessionId)
            // Closing the transport is what cascades (Protocol wires transport.onClose into the
            // session close) into the SDK Server deregistering the session; removing it from the
            // local map alone does not.
            runCatching { transport.close() }
            LOG.info("Legacy SSE session closed: ${transport.sessionId}")
        }
    }

    private suspend fun RoutingContext.handleLegacyOrStatelessPost() {
        val call = call.withLenientMcpHeaders()
        val sessionId = call.request.queryParameters[McpConstants.SESSION_ID_PARAM]
        if (!sessionId.isNullOrBlank()) {
            val transport = sseTransports[sessionId]
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found: $sessionId")
                return
            }
            transport.handlePostMessage(call)
            return
        }

        serveStatelessRequest(call)
    }

    /**
     * One transport and one session per request, discarded afterwards — no handshake, no session
     * header, no server state. This is what makes `curl` and the tool-behaviour tests work.
     */
    private suspend fun serveStatelessRequest(call: ApplicationCall) {
        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
        ).also { it.setSessionIdGenerator(null) }

        mcpServer.createSession(transport)
        try {
            transport.handleRequest(null, call)
        } finally {
            // One session per request means one *release* per request: Server.createSession only
            // deregisters a session when its transport closes, and this transport has no other
            // close path (no DELETE ever arrives here). Without this, every stateless call leaks
            // a ServerSession plus its notification coroutine into the shared SDK Server. The
            // SDK's own stateless endpoint hides the same behaviour only by building a throwaway
            // Server per request. Pinned by LegacyTransportTest.
            runCatching { transport.close() }
        }
    }
}
