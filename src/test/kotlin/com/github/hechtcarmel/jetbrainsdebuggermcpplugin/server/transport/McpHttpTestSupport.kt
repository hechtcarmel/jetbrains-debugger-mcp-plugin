package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpToolBridge
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.KtorMcpServer
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Boots the real [KtorMcpServer] on a free port and drives it over real HTTP.
 *
 * ## Why this exists
 *
 * Everything asserted through this harness is *client-observable behaviour*: the tests describe
 * what an MCP client sees over real HTTP, not how the server is built. That is what allowed the
 * hand-rolled protocol layer to be replaced by the MCP Kotlin SDK with the suite as the referee —
 * and it is what will keep an SDK version bump honest next time.
 *
 * ## Why the JDK HTTP client
 *
 * `java.net.http.HttpClient` ships with the JDK, so the transport tests add no dependency. The
 * project has no Ktor *client* on the test classpath and does not need one.
 */
abstract class McpHttpTestCase : BasePlatformTestCase() {

    protected val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: KtorMcpServer
    private lateinit var http: HttpClient
    protected var port: Int = 0

    protected lateinit var registry: ToolRegistry
        private set

    /** The SDK server behind the HTTP edge — exposed so tests can assert on session lifecycle. */
    protected lateinit var mcpServer: io.modelcontextprotocol.kotlin.sdk.server.Server
        private set

    override fun setUp() {
        super.setUp()
        registry = ToolRegistry().apply { registerBuiltInTools() }
        port = freePort()
        mcpServer = McpServerFactory.create(registry, McpToolBridge())
        server = KtorMcpServer(
            port = port,
            mcpServer = mcpServer,
        )
        val result = server.start()
        assertEquals("MCP server failed to start on port $port: $result", KtorMcpServer.StartResult.Success, result)
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        awaitServerReady()
    }

    override fun tearDown() {
        try {
            if (::http.isInitialized) http.close()
            if (::server.isInitialized) server.stop()
        } finally {
            super.tearDown()
        }
    }

    /**
     * CIO's `start(wait = false)` returns before the socket is necessarily accepting, so poll
     * briefly rather than racing the first request.
     */
    private fun awaitServerReady() {
        val deadline = System.currentTimeMillis() + 10_000
        var lastFailure: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return
            } catch (e: Exception) {
                lastFailure = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("MCP server never accepted connections on port $port", lastFailure)
    }

    // ponytail: retry rather than lock. The window between releasing the probe socket and the
    // server binding is tiny, and the suite runs single-forked.
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ── Requests ────────────────────────────────────────────────────────────────────────

    protected fun url(path: String): String = "http://127.0.0.1:$port$path"

    protected fun post(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> = send(requestBuilder(path, headers).POST(HttpRequest.BodyPublishers.ofString(body)))

    protected fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        send(requestBuilder(path, headers).GET())

    /**
     * Opens an SSE stream and returns as soon as the response *headers* arrive.
     *
     * A plain [get] would block until the server closed the body, which for an event stream is
     * never — the request would sit there until its timeout and report a misleading failure.
     * The caller gets the status and headers, and the lazy line stream, which it should close.
     */
    protected fun openEventStream(
        path: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<java.util.stream.Stream<String>> = pumpingEdt {
        http.sendAsync(
            requestBuilder(path, headers).GET().build(),
            HttpResponse.BodyHandlers.ofLines(),
        ).get(20, TimeUnit.SECONDS)
    }

    protected fun delete(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        send(requestBuilder(path, headers).DELETE())

    protected fun options(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        send(requestBuilder(path, headers).method("OPTIONS", HttpRequest.BodyPublishers.noBody()))

    private fun requestBuilder(path: String, headers: Map<String, String>): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url(path)))
            // Must exceed pumpingEdt's own deadline, so a genuine EDT deadlock surfaces as the
            // pump timeout (which names the cause) rather than as a misleading HTTP timeout.
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "application/json")
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
            builder.header("Accept", "application/json, text/event-stream")
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

    // ── JSON-RPC helpers ────────────────────────────────────────────────────────────────

    protected fun rpc(method: String, id: Any? = 1, params: String? = null): String = buildString {
        append("""{"jsonrpc":"2.0"""")
        if (id != null) append(""","id":${if (id is String) "\"$id\"" else id}""")
        append(""","method":"$method"""")
        if (params != null) append(""","params":$params""")
        append("}")
    }

    protected fun toolCall(name: String, arguments: String = "{}"): String =
        rpc("tools/call", params = """{"name":"$name","arguments":$arguments}""")

    protected fun HttpResponse<String>.jsonBody(): JsonObject = json.parseToJsonElement(body()).jsonObject

    protected fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

    /**
     * Completes the streamable-HTTP handshake and returns the issued session id.
     *
     * Every non-initialize request on that transport is rejected without it — the SDK's session
     * state machine answers `-32000 "Server not initialized"`.
     */
    protected fun initializeStreamable(): String {
        val response = post(
            McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            rpc("initialize", params = """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")
        )
        assertEquals("initialize should succeed: ${response.body()}", 200, response.statusCode())
        return requireNotNull(response.header(McpConstants.MCP_SESSION_ID_HEADER)) {
            "initialize did not issue an ${McpConstants.MCP_SESSION_ID_HEADER} header"
        }
    }

    protected fun sessionHeaders(sessionId: String): Map<String, String> =
        mapOf(McpConstants.MCP_SESSION_ID_HEADER to sessionId)

    /**
     * Runs [block] on a background thread while pumping the IDE event queue, and returns its result.
     *
     * ## Why this is necessary
     *
     * `BasePlatformTestCase` runs test methods **on the EDT**. Tools that mutate IDE state hop onto
     * the EDT themselves — `set_breakpoint`, for example, wraps `toggleLineBreakpoint` in
     * `withContext(Dispatchers.Main) { invokeAndWait { ... } }`. So a plain blocking HTTP call from
     * a test deadlocks instantly: the test thread *is* the EDT and is blocked waiting for a
     * response that cannot be produced until the EDT runs.
     *
     * The symptom is a 30-second `HttpTimeoutException`, which reads like a slow server rather than
     * a deadlock — hence this helper rather than a longer timeout.
     *
     * Tests that only read state (`tools/list`, `list_breakpoints`) do not need it, but using it
     * uniformly for tool calls costs nothing and avoids having to know which tools touch the EDT.
     */
    protected fun <T> pumpingEdt(timeoutMs: Long = 60_000, block: () -> T): T {
        val future = CompletableFuture.supplyAsync(block)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        check(future.isDone) {
            "Timed out after ${timeoutMs}ms while pumping the EDT — the call under test is most " +
                "likely blocked waiting on the EDT that this thread is pumping."
        }
        // future.get wraps whatever block() threw in an ExecutionException, which buries the
        // assertion message the test author actually wrote. Rethrow the cause.
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
