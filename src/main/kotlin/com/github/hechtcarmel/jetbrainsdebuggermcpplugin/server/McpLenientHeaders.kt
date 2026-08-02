package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentType

/**
 * Restores the pre-SDK behaviour of treating `Accept` and `Content-Type` as advisory on the MCP
 * POST endpoints.
 *
 * The SDK transports enforce both: the Streamable HTTP transport rejects any POST whose `Accept`
 * does not contain *both* `application/json` and `text/event-stream` — a **substring** check, so
 * the wildcard accept-anything header fails it — and both transports reject non-JSON
 * `Content-Type`s. `curl` sends exactly those two defaults (wildcard accept, form-urlencoded), and this
 * plugin's connection-issue history (#18, #23, #24) is exactly the argument for not letting a
 * header the server never used to read start rejecting clients that worked yesterday.
 *
 * These routes speak only JSON-RPC, so normalising the two headers changes what a non-conformant
 * request *receives* in no way the old server didn't already: a JSON body parses or fails with
 * `-32700`, same as before.
 *
 * Conformant requests are passed through untouched.
 */
internal fun ApplicationCall.withLenientMcpHeaders(): ApplicationCall {
    val accept = request.headers[HttpHeaders.Accept]
    val acceptOk = accept != null &&
        accept.contains("application/json", ignoreCase = true) &&
        accept.contains("text/event-stream", ignoreCase = true)
    val contentTypeOk = runCatching {
        request.contentType().withoutParameters().match(ContentType.Application.Json)
    }.getOrDefault(false)

    if (acceptOk && contentTypeOk) return this
    return LenientHeadersCall(this, fixAccept = !acceptOk, fixContentType = !contentTypeOk)
}

private class LenientHeadersCall(
    delegate: ApplicationCall,
    fixAccept: Boolean,
    fixContentType: Boolean,
) : ApplicationCall by delegate {

    override val request: ApplicationRequest = object : ApplicationRequest by delegate.request {
        override val headers: Headers = HeadersBuilder().apply {
            appendAll(delegate.request.headers)
            if (fixAccept) {
                remove(HttpHeaders.Accept)
                append(HttpHeaders.Accept, "application/json, text/event-stream")
            }
            if (fixContentType) {
                remove(HttpHeaders.ContentType)
                append(HttpHeaders.ContentType, "application/json")
            }
        }.build()
    }
}
