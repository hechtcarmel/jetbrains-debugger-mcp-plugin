package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants

/**
 * Pins the Origin allow-list — the plugin's **only** security control.
 *
 * There is no authentication, no Host validation and no Accept-header requirement. The single
 * thing standing between a web page and a live debugger on the user's machine is
 * `KtorMcpServer.validateOrigin`, and that method lives in the class the MCP SDK migration
 * deletes. If it is not deliberately re-implemented as an interceptor in front of the SDK's route,
 * the endpoint silently becomes reachable from any origin — with no compile error and, before
 * this file, no failing test.
 *
 * The absent-Origin allowance is pinned too, because it is load-bearing: `curl` and several MCP
 * clients send no Origin at all, so tightening it would break them. That makes it a deliberate
 * trade-off rather than an oversight, and the test records which.
 */
class OriginGuardTest : McpHttpTestCase() {

    private val path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH

    private fun origin(value: String) = mapOf("Origin" to value)

    fun `test loopback origins are allowed and echoed back`() {
        listOf(
            "http://localhost:3000",
            "http://127.0.0.1:8080",
            "https://localhost",
            "http://[::1]:5173",
        ).forEach { allowed ->
            val response = post(path, rpc("initialize", params = "{}"), origin(allowed))

            assertEquals("$allowed should be allowed", 200, response.statusCode())
            assertEquals(
                "CORS must echo the caller's origin",
                allowed,
                response.header("Access-Control-Allow-Origin")
            )
            assertEquals(
                "Clients need the session header exposed to read it from JS",
                McpConstants.MCP_SESSION_ID_HEADER,
                response.header("Access-Control-Expose-Headers")
            )
        }
    }

    fun `test non-loopback origins are rejected with 403`() {
        listOf(
            "http://evil.example.com",
            "https://attacker.test",
            "http://localhost.evil.com",
            "http://127.0.0.1.evil.com",
        ).forEach { blocked ->
            val response = post(path, rpc("initialize", params = "{}"), origin(blocked))

            assertEquals("$blocked must be rejected", 403, response.statusCode())
            assertNull(
                "A rejected origin must not receive CORS approval",
                response.header("Access-Control-Allow-Origin")
            )
        }
    }

    fun `test non-http schemes are rejected`() {
        listOf("file://", "null", "chrome-extension://abcdef").forEach { blocked ->
            assertEquals(
                "$blocked must be rejected",
                403,
                post(path, rpc("initialize", params = "{}"), origin(blocked)).statusCode()
            )
        }
    }

    /**
     * A request with no Origin header at all is allowed. This is deliberate — `curl` and several
     * MCP clients send none — and it is also the guard's widest gap, since a non-browser attacker
     * simply omits the header. Recorded here so the trade-off is visible rather than assumed.
     */
    fun `test absent origin is allowed without CORS headers`() {
        val response = post(path, rpc("initialize", params = "{}"))

        assertEquals(200, response.statusCode())
        assertNull(
            "No Origin means no CORS headers are needed",
            response.header("Access-Control-Allow-Origin")
        )
    }

    fun `test the guard applies to every endpoint not just the primary one`() {
        listOf(
            McpConstants.MCP_ENDPOINT_PATH,
            McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
        ).forEach { endpoint ->
            assertEquals(
                "$endpoint must enforce the Origin allow-list",
                403,
                post(endpoint, rpc("initialize", params = "{}"), origin("http://evil.example.com")).statusCode()
            )
        }

        assertEquals(
            "The SSE endpoint must enforce it too",
            403,
            get(McpConstants.SSE_ENDPOINT_PATH, origin("http://evil.example.com")).statusCode()
        )
    }

    fun `test preflight advertises the methods and headers clients need`() {
        val response = options(path, origin("http://localhost:3000") + mapOf("Access-Control-Request-Method" to "POST"))

        assertEquals(204, response.statusCode())
        assertEquals("http://localhost:3000", response.header("Access-Control-Allow-Origin"))

        val allowedMethods = response.header("Access-Control-Allow-Methods").orEmpty()
        listOf("POST", "GET", "DELETE").forEach {
            assertTrue("Preflight should advertise $it, was: $allowedMethods", allowedMethods.contains(it))
        }

        val allowedHeaders = response.header("Access-Control-Allow-Headers").orEmpty()
        listOf("Content-Type", McpConstants.MCP_SESSION_ID_HEADER).forEach {
            assertTrue("Preflight should advertise $it, was: $allowedHeaders", allowedHeaders.contains(it))
        }
    }

    fun `test preflight from a disallowed origin is refused`() {
        val response = options(path, origin("http://evil.example.com") + mapOf("Access-Control-Request-Method" to "POST"))

        assertEquals(403, response.statusCode())
    }
}
