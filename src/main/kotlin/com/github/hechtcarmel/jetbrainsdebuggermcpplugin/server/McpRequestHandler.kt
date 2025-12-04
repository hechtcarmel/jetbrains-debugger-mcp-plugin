package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.JsonRpcError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.JsonRpcResponse
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets

/**
 * MCP Request Handler implementing both SSE and Streamable HTTP transports.
 *
 * Supports two MCP transport modes:
 *
 * 1. SSE Transport (2024-11-05 spec):
 *    - GET /debugger-mcp/sse → Opens SSE stream, sends `endpoint` event with POST URL including sessionId
 *    - POST /debugger-mcp?sessionId=xxx → JSON-RPC messages, response sent via SSE `message` event
 *
 * 2. Streamable HTTP Transport (for clients like Claude Code):
 *    - POST /debugger-mcp (no sessionId) → JSON-RPC messages, immediate JSON response
 *
 * The transport mode is auto-detected based on presence of sessionId parameter.
 *
 * @see <a href="https://modelcontextprotocol.io/docs/concepts/transports#http-with-sse">MCP HTTP+SSE Transport</a>
 */
class McpRequestHandler : HttpRequestHandler() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<McpRequestHandler>()
        const val MCP_PATH = McpConstants.MCP_ENDPOINT_PATH
        const val SSE_PATH = McpConstants.SSE_ENDPOINT_PATH
        const val SESSION_ID_PARAM = McpConstants.SESSION_ID_PARAM
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        val uri = request.uri()
        return uri.startsWith(MCP_PATH)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val path = urlDecoder.path()

        if (!path.startsWith(MCP_PATH)) {
            return false
        }

        return when {
            // GET /debugger-mcp/sse → SSE stream
            request.method() == HttpMethod.GET && path == SSE_PATH -> {
                handleSseRequest(context)
                true
            }
            // POST /debugger-mcp?sessionId=xxx → JSON-RPC via SSE
            request.method() == HttpMethod.POST && (path == MCP_PATH || path == SSE_PATH) -> {
                handlePostRequest(urlDecoder, request, context)
                true
            }
            // OPTIONS for CORS
            request.method() == HttpMethod.OPTIONS -> {
                handleOptionsRequest(context)
                true
            }
            else -> false
        }
    }

    /**
     * GET /debugger-mcp/sse → Opens SSE stream.
     *
     * Creates a new SSE session and sends the `endpoint` event with the POST URL
     * including the session ID. The connection is kept open for sending responses.
     */
    private fun handleSseRequest(context: ChannelHandlerContext) {
        LOG.info("Opening SSE connection for debugger MCP")

        val mcpService = ApplicationManager.getApplication().service<McpServerService>()
        val sessionManager = mcpService.getSseSessionManager()

        // Create a new session
        val sessionId = sessionManager.createSession(context)

        // Send HTTP response headers for SSE
        val response = DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        )

        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, "keep-alive")
        }
        addCorsHeaders(response)

        context.write(response)

        // Send the endpoint event with relative URL including session ID
        // Using relative URL as per MCP spec - client will resolve against connection origin
        val endpointPath = "$MCP_PATH?$SESSION_ID_PARAM=$sessionId"

        val endpointEvent = "event: endpoint\ndata: $endpointPath\n\n"
        val buffer = Unpooled.copiedBuffer(endpointEvent, StandardCharsets.UTF_8)
        context.writeAndFlush(DefaultHttpContent(buffer))

        LOG.info("SSE session established: $sessionId, endpoint: $endpointPath")
    }

    /**
     * POST /debugger-mcp → Handles JSON-RPC requests.
     *
     * Supports two modes:
     * - With sessionId: SSE transport - sends response via SSE `message` event, returns 202 Accepted
     * - Without sessionId: Streamable HTTP - returns immediate JSON response
     */
    private fun handlePostRequest(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ) {
        val body = request.content().toString(StandardCharsets.UTF_8)

        // Extract session ID from query parameters (optional)
        val sessionIdParams = urlDecoder.parameters()[SESSION_ID_PARAM]
        val sessionId = sessionIdParams?.firstOrNull()

        val mcpService = ApplicationManager.getApplication().service<McpServerService>()

        // Determine transport mode based on sessionId presence
        if (sessionId.isNullOrBlank()) {
            // Streamable HTTP mode - immediate JSON response
            handleStreamableHttpRequest(body, context, mcpService)
        } else {
            // SSE transport mode - response via SSE stream
            handleSsePostRequest(sessionId, body, context, mcpService)
        }
    }

    /**
     * Handles POST request in Streamable HTTP mode (no sessionId).
     * Returns immediate JSON response.
     */
    private fun handleStreamableHttpRequest(
        body: String,
        context: ChannelHandlerContext,
        mcpService: McpServerService
    ) {
        if (body.isBlank()) {
            sendJsonRpcError(context, null, JsonRpcErrorCodes.PARSE_ERROR, "Empty request body")
            return
        }

        // Process request asynchronously
        mcpService.coroutineScope.launch {
            try {
                val response = mcpService.getJsonRpcHandler().handleRequest(body)

                // Send response back on Netty event loop thread
                context.channel().eventLoop().execute {
                    if (context.channel().isActive) {
                        sendJsonResponse(context, HttpResponseStatus.OK, response)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error processing debugger MCP request (Streamable HTTP)", e)

                context.channel().eventLoop().execute {
                    if (context.channel().isActive) {
                        sendJsonRpcError(
                            context,
                            null,
                            JsonRpcErrorCodes.INTERNAL_ERROR,
                            e.message ?: "Internal error"
                        )
                    }
                }
            }
        }
    }

    /**
     * Handles POST request in SSE transport mode (with sessionId).
     * Sends response via SSE `message` event, returns 202 Accepted immediately.
     */
    private fun handleSsePostRequest(
        sessionId: String,
        body: String,
        context: ChannelHandlerContext,
        mcpService: McpServerService
    ) {
        val sessionManager = mcpService.getSseSessionManager()

        // Verify session exists
        val session = sessionManager.getSession(sessionId)
        if (session == null) {
            LOG.warn("POST request with invalid sessionId: $sessionId")
            sendHttpError(
                context,
                HttpResponseStatus.NOT_FOUND,
                "Session not found: $sessionId"
            )
            return
        }

        if (body.isBlank()) {
            // Send error via SSE
            val errorResponse = JsonRpcResponse(
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.PARSE_ERROR,
                    message = "Empty request body"
                )
            )
            sessionManager.sendEvent(sessionId, "message", json.encodeToString(errorResponse))
            sendAccepted(context)
            return
        }

        // Return 202 Accepted immediately
        sendAccepted(context)

        // Process request asynchronously and send response via SSE
        mcpService.coroutineScope.launch {
            try {
                val response = mcpService.getJsonRpcHandler().handleRequest(body)

                // Send response as SSE message event
                val sent = sessionManager.sendEvent(sessionId, "message", response)
                if (!sent) {
                    LOG.warn("Failed to send response to session $sessionId - session may have closed")
                }
            } catch (e: Exception) {
                LOG.error("Error processing debugger MCP request (SSE)", e)

                val errorResponse = JsonRpcResponse(
                    error = JsonRpcError(
                        code = JsonRpcErrorCodes.INTERNAL_ERROR,
                        message = e.message ?: "Internal error"
                    )
                )
                sessionManager.sendEvent(sessionId, "message", json.encodeToString(errorResponse))
            }
        }
    }

    /**
     * OPTIONS → CORS preflight.
     */
    private fun handleOptionsRequest(context: ChannelHandlerContext) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        )
        addCorsHeaders(response)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        context.writeAndFlush(response)
    }

    /**
     * Sends a 202 Accepted response.
     */
    private fun sendAccepted(context: ChannelHandlerContext) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.ACCEPTED
        )
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        addCorsHeaders(response)
        context.writeAndFlush(response)
    }

    /**
     * Sends an HTTP error response.
     */
    private fun sendHttpError(
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        message: String
    ) {
        val content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            content
        )

        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
            set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        }
        addCorsHeaders(response)

        context.writeAndFlush(response)
    }

    /**
     * Sends a JSON response (for Streamable HTTP mode).
     */
    private fun sendJsonResponse(
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        jsonContent: String
    ) {
        val content = Unpooled.copiedBuffer(jsonContent, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            content
        )

        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
            set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        }
        addCorsHeaders(response)

        context.writeAndFlush(response)
    }

    /**
     * Sends a JSON-RPC error response (for Streamable HTTP mode).
     */
    private fun sendJsonRpcError(
        context: ChannelHandlerContext,
        id: JsonElement?,
        code: Int,
        message: String
    ) {
        val errorResponse = JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
        sendJsonResponse(context, HttpResponseStatus.OK, json.encodeToString(errorResponse))
    }

    private fun addCorsHeaders(response: HttpResponse) {
        response.headers().apply {
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Accept")
        }
    }
}
