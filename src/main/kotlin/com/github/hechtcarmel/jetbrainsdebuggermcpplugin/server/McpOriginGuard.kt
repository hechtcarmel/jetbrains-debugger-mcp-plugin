package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import java.net.URI

/**
 * The plugin's only access control: an MCP request may only carry an `Origin` from loopback.
 *
 * The server binds to `127.0.0.1` by default but is user-configurable all the way to `0.0.0.0`, and
 * there is no authentication of any kind — so this guard is what stops a page in the user's browser
 * from driving their debugger. It is deliberately kept as plugin code rather than delegated to the
 * SDK's DNS-rebinding protection, because its exact semantics are pinned by `OriginGuardTest` and
 * differ from the SDK's in two ways that matter:
 *
 *  - **A missing `Origin` is allowed.** `curl` and several MCP clients send none, and rejecting
 *    those would break every one of them. Browsers always send it, which is the threat this guards.
 *  - **Preflight is stricter than the request it precedes**: `OPTIONS` requires a present *and*
 *    allowed `Origin`, because a preflight with no `Origin` is not a real preflight.
 */
internal object McpOriginGuard {

    private val LOG = logger<McpOriginGuard>()

    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val ALLOWED_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    private val PREFLIGHT_METHODS =
        listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete, HttpMethod.Options)
            .joinToString(", ") { it.value }

    private val PREFLIGHT_HEADERS =
        listOf(HttpHeaders.ContentType, HttpHeaders.Accept, McpConstants.MCP_SESSION_ID_HEADER)
            .joinToString(", ")

    /**
     * Applies the guard to every request under this route except `OPTIONS`, which
     * [respondToPreflight] owns.
     *
     * Installed as a pipeline interceptor rather than as a call hook because it must be able to
     * *abort*: a plugin that only observes the call cannot stop the handler from running, and a
     * rejected Origin that still reaches the transport is not a guard.
     */
    fun install(route: Route) {
        // Ktor 3 moved the pipeline off the `Route` interface and onto its `RoutingNode`
        // implementation; `intercept` + `finish()` is still the only routing API that can abort a
        // call before its handler runs, which is exactly what a guard has to do. The cast is safe:
        // `routing { route(...) { } }` always yields a RoutingNode.
        val node = route as? RoutingNode
            ?: error("Origin guard needs a RoutingNode to intercept, got ${route::class.java.name}")

        node.intercept(ApplicationCallPipeline.Plugins) {
            // OPTIONS is stricter and is owned by respondToPreflight; leave it alone.
            if (call.request.httpMethod == HttpMethod.Options) return@intercept
            if (!validate(call)) finish()
        }
    }

    /**
     * @return true when the request may proceed. Rejects by responding, so callers must not respond
     *   again.
     */
    private suspend fun validate(call: ApplicationCall): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin] ?: return true

        if (isAllowed(origin)) {
            applyCorsHeaders(call, origin)
            return true
        }

        LOG.warn("Rejected MCP request with invalid Origin header: $origin")

        // A browser reading an SSE stream cannot parse a JSON-RPC envelope out of a failed
        // EventSource, so GET rejections stay plain text.
        if (call.request.httpMethod == HttpMethod.Get) {
            call.respondText("Origin not allowed", status = HttpStatusCode.Forbidden)
        } else {
            call.respondText(
                McpJsonRpcErrors.originNotAllowed(),
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
        return false
    }

    /** Handles an `OPTIONS` preflight. */
    suspend fun respondToPreflight(call: ApplicationCall) {
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin.isNullOrBlank() || !isAllowed(origin)) {
            call.respondText("Origin not allowed", status = HttpStatusCode.Forbidden)
            return
        }

        applyCorsHeaders(call, origin)
        call.response.header(HttpHeaders.AccessControlAllowMethods, PREFLIGHT_METHODS)
        call.response.header(HttpHeaders.AccessControlAllowHeaders, PREFLIGHT_HEADERS)
        call.respond(HttpStatusCode.NoContent)
    }

    private fun applyCorsHeaders(call: ApplicationCall, origin: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        // Streamable HTTP clients must read the session id off the response.
        call.response.header(HttpHeaders.AccessControlExposeHeaders, McpConstants.MCP_SESSION_ID_HEADER)
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
    }

    /**
     * Host must match exactly — `localhost.evil.com` is not `localhost`. The port is deliberately
     * ignored: dev servers pick arbitrary ports and the origin is still loopback.
     */
    private fun isAllowed(origin: String): Boolean {
        val uri = try {
            URI(origin)
        } catch (_: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: return false
        val host = normalizeHost(uri.host?.lowercase() ?: return false)
        return scheme in ALLOWED_SCHEMES && host in ALLOWED_HOSTS
    }

    /** `http://[::1]:5173` parses to a bracketed host; compare it unbracketed. */
    private fun normalizeHost(host: String): String = host.removePrefix("[").removeSuffix("]")
}
