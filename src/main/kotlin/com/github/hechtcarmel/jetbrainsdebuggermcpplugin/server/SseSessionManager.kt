package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active SSE sessions for the MCP server.
 *
 * Each SSE connection is assigned a unique session ID. When a client POSTs
 * a JSON-RPC request, the session ID is used to route the response back
 * through the correct SSE stream.
 *
 * Thread-safe using ConcurrentHashMap.
 */
class SseSessionManager {

    private val sessions = ConcurrentHashMap<String, SseSession>()

    companion object {
        private val LOG = logger<SseSessionManager>()
    }

    /**
     * Creates a new SSE session and registers the channel.
     *
     * @param context The Netty channel context for the SSE connection
     * @return The generated session ID
     */
    fun createSession(context: ChannelHandlerContext): String {
        val sessionId = UUID.randomUUID().toString()
        val session = SseSession(sessionId, context)
        sessions[sessionId] = session

        // Register close listener to clean up session when connection closes
        context.channel().closeFuture().addListener {
            removeSession(sessionId)
        }

        LOG.info("Created SSE session: $sessionId (active sessions: ${sessions.size})")
        return sessionId
    }

    /**
     * Gets an active session by ID.
     *
     * @param sessionId The session ID
     * @return The session, or null if not found or closed
     */
    fun getSession(sessionId: String): SseSession? {
        val session = sessions[sessionId]
        if (session != null && !session.isActive()) {
            // Clean up stale session
            removeSession(sessionId)
            return null
        }
        return session
    }

    /**
     * Removes a session by ID.
     *
     * @param sessionId The session ID to remove
     */
    fun removeSession(sessionId: String) {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            LOG.info("Removed SSE session: $sessionId (active sessions: ${sessions.size})")
        }
    }

    /**
     * Sends an SSE event to a specific session.
     *
     * @param sessionId The session ID
     * @param eventType The SSE event type (e.g., "message", "endpoint")
     * @param data The event data
     * @return true if sent successfully, false if session not found or inactive
     */
    fun sendEvent(sessionId: String, eventType: String, data: String): Boolean {
        val session = getSession(sessionId)
        if (session == null) {
            LOG.warn("Cannot send event to session $sessionId: session not found")
            return false
        }
        return session.sendEvent(eventType, data)
    }

    /**
     * Gets the number of active sessions.
     */
    fun getActiveSessionCount(): Int = sessions.size

    /**
     * Closes all active sessions.
     */
    fun closeAllSessions() {
        sessions.keys.toList().forEach { sessionId ->
            getSession(sessionId)?.close()
            removeSession(sessionId)
        }
    }
}

/**
 * Represents an active SSE session.
 *
 * @param sessionId The unique session identifier
 * @param context The Netty channel context for sending events
 */
class SseSession(
    val sessionId: String,
    private val context: ChannelHandlerContext
) {
    companion object {
        private val LOG = logger<SseSession>()
    }

    /**
     * Checks if the session's channel is still active.
     */
    fun isActive(): Boolean = context.channel().isActive

    /**
     * Sends an SSE event over this session's channel.
     *
     * @param eventType The SSE event type
     * @param data The event data (will be sent as-is in the data field)
     * @return true if sent successfully
     */
    fun sendEvent(eventType: String, data: String): Boolean {
        if (!isActive()) {
            LOG.warn("Cannot send event to session $sessionId: channel inactive")
            return false
        }

        return try {
            // SSE spec: each line of data must be prefixed with "data: "
            // This handles multiline data correctly
            val dataLines = data.lines().joinToString("\n") { "data: $it" }
            val sseEvent = "event: $eventType\n$dataLines\n\n"
            val buffer = Unpooled.copiedBuffer(sseEvent, StandardCharsets.UTF_8)

            // Send on the event loop thread to ensure thread safety
            // Netty handles inactive channels gracefully (fails write, releases buffer)
            context.channel().eventLoop().execute {
                context.writeAndFlush(DefaultHttpContent(buffer))
            }
            true
        } catch (e: Exception) {
            LOG.error("Failed to send SSE event to session $sessionId", e)
            false
        }
    }

    /**
     * Closes the SSE connection.
     */
    fun close() {
        if (isActive()) {
            context.close()
        }
    }
}
