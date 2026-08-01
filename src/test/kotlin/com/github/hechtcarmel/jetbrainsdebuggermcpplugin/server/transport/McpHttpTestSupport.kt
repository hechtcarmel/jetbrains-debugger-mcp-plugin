package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.KtorMcpServer
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.KtorSseSessionManager
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.StreamableHttpSessionManager
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Before this class the entire transport layer — every route, status code, header, Origin
 * decision, session-id rule and SSE frame — was verified by nothing. The pre-existing server
 * tests called [JsonRpcHandler] directly with hand-built envelopes, which cannot observe anything
 * Ktor does, and which are scheduled for deletion along with the handler when the plugin migrates
 * to the official MCP Kotlin SDK.
 *
 * Everything asserted through this harness is *client-observable behaviour*, so it stays
 * meaningful across that migration: the tests describe what an MCP client sees, not how the
 * server is built.
 *
 * ## Why the JDK HTTP client
 *
 * `java.net.http.HttpClient` ships with the JDK, so the transport tests add no dependency. The
 * project has no Ktor *client* on the test classpath and does not need one.
 */
abstract class McpHttpTestCase : BasePlatformTestCase() {

    protected val json = Json { ignoreUnknownKeys = true }

    private lateinit var scope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private lateinit var http: HttpClient
    protected var port: Int = 0

    protected lateinit var registry: ToolRegistry
        private set

    override fun setUp() {
        super.setUp()
        registry = ToolRegistry().apply { registerBuiltInTools() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        port = freePort()
        server = KtorMcpServer(
            port = port,
            jsonRpcHandler = JsonRpcHandler(registry),
            sseSessionManager = KtorSseSessionManager(),
            streamableHttpSessionManager = StreamableHttpSessionManager(),
            coroutineScope = scope
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
            if (::scope.isInitialized) scope.cancel()
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

    protected fun delete(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        send(requestBuilder(path, headers).DELETE())

    protected fun options(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        send(requestBuilder(path, headers).method("OPTIONS", HttpRequest.BodyPublishers.noBody()))

    private fun requestBuilder(path: String, headers: Map<String, String>): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url(path)))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
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

    protected fun HttpResponse<String>.header(name: String): String? = headers().firstValue(name).orElse(null)

    /**
     * Completes the streamable-HTTP handshake and returns the issued session id.
     *
     * Every non-initialize request on that transport is rejected without it
     * (KtorMcpServer.validateStreamableSession).
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
        check(future.isDone) { "Timed out after ${timeoutMs}ms while pumping the EDT" }
        return future.get(10, TimeUnit.SECONDS)
    }
}
