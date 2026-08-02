package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

/**
 * The few JSON-RPC error envelopes the HTTP edge emits before a request ever reaches the SDK.
 *
 * Everything past the transport boundary is the SDK's to encode; these exist only for failures that
 * happen first — an Origin the guard refuses, or a session that does not exist — where there is no
 * session to encode a reply through.
 */
internal object McpJsonRpcErrors {

    private const val INVALID_REQUEST = -32600

    fun originNotAllowed(): String = envelope(INVALID_REQUEST, "Origin not allowed")

    private fun envelope(code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":null,"error":{"code":$code,"message":"$message"}}"""
}
