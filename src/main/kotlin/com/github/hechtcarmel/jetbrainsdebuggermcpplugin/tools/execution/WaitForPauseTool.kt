package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.WaitForPauseResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.SessionStatusCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Waits for a debug session to pause (breakpoint hit, exception, or manual pause).
 *
 * Returns the full session status when paused, equivalent to calling get_debug_session_status.
 * Uses XDebugSessionListener for event-driven notification — no polling.
 */
class WaitForPauseTool : AbstractMcpTool() {

    override val name = "wait_for_pause"

    override val description = """
        Waits for a debug session to pause (breakpoint hit, exception, or manual pause). Returns the full session status when paused, equivalent to calling get_debug_session_status.
        Use after resume_execution, start_debug_session, or any execution control tool to avoid manual polling.
        The timeout parameter is required and specifies the maximum wait time in seconds.
        Optionally filter by breakpoint_ids to only return when specific breakpoints are hit — non-matching breakpoint pauses are auto-resumed.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Wait For Pause")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("waitResult") { put("type", "string"); put("description", "Why the wait completed: 'paused', 'timeout', or 'session_stopped'") }
            putJsonObject("message") { put("type", "string"); put("description", "Human-readable description of the wait outcome") }
            putJsonObject("sessionId") { put("type", "string"); put("description", "Debug session ID") }
            putJsonObject("name") { put("type", "string"); put("description", "Debug session display name") }
            putJsonObject("state") { put("type", "string"); put("description", "Session state: 'running', 'paused', or 'stopped'") }
            putJsonObject("pausedReason") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Why execution paused: 'breakpoint', 'step', 'exception', or 'pause'") }
            putJsonObject("currentLocation") {
                putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
                putJsonObject("properties") {
                    putJsonObject("file") { put("type", "string") }
                    putJsonObject("line") { put("type", "integer") }
                    putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                    putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                }
                put("description", "Current execution location")
            }
            putJsonObject("variables") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("value") { put("type", "string") }
                        putJsonObject("type") { put("type", "string") }
                        putJsonObject("hasChildren") { put("type", "boolean") }
                    }
                }
                put("description", "Variables visible in current stack frame")
            }
            putJsonObject("stackSummary") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("index") { put("type", "integer") }
                        putJsonObject("file") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                        putJsonObject("line") { putJsonArray("type") { add(JsonPrimitive("integer")); add(JsonPrimitive("null")) } }
                        putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                        putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                    }
                }
                put("description", "Stack trace summary")
            }
            putJsonObject("sourceContext") {
                putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
                put("description", "Source code around the current execution point")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("waitResult")); add(JsonPrimitive("message")); add(JsonPrimitive("sessionId")); add(JsonPrimitive("name")); add(JsonPrimitive("state")) })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("timeout") {
                put("type", "integer")
                put("description", "Maximum wait time in seconds. Must be positive.")
                put("minimum", 1)
            }
            putJsonObject("breakpoint_ids") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "If set, only complete when one of these breakpoints is hit. Non-matching breakpoint pauses are auto-resumed. Exception and manual pauses always return immediately regardless of filter.")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("timeout"))
        })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val timeoutSeconds = arguments["timeout"]?.jsonPrimitive?.intOrNull
            ?: return createErrorResult("Missing required parameter: timeout")
        val breakpointIds = arguments["breakpoint_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()

        if (timeoutSeconds < 1) {
            return createErrorResult("timeout must be positive")
        }

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (session.isStopped) {
            return createJsonResult(buildStoppedResult(session))
        }

        val timeoutMs = timeoutSeconds * 1000L
        val startTime = System.currentTimeMillis()

        val deferred = CompletableDeferred<WaitOutcome>()

        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                handlePause(session, breakpointIds, deferred, startTime, timeoutMs)
            }

            override fun sessionStopped() {
                deferred.complete(WaitOutcome.SessionStopped)
            }
        }

        // Register listener and check state atomically on EDT
        onEdt {
            session.addSessionListener(listener)
            when {
                session.isStopped -> {
                    deferred.complete(WaitOutcome.SessionStopped)
                }
                session.isPaused -> {
                    handlePause(session, breakpointIds, deferred, startTime, timeoutMs)
                }
            }
        }

        try {
            val remainingMs = timeoutMs - (System.currentTimeMillis() - startTime)
            val outcome = if (remainingMs > 0) {
                withTimeoutOrNull(remainingMs) { deferred.await() }
            } else {
                if (deferred.isCompleted) deferred.await() else null
            }

            return when (outcome) {
                is WaitOutcome.Paused -> {
                    val status = SessionStatusCollector.collectStatus(project, session)
                    createJsonResult(WaitForPauseResult(
                        waitResult = "paused",
                        message = "Session paused: ${status.pausedReason ?: "unknown"}",
                        sessionId = status.sessionId,
                        name = status.name,
                        state = status.state,
                        pausedReason = status.pausedReason,
                        currentLocation = status.currentLocation,
                        breakpointHit = status.breakpointHit,
                        stackSummary = status.stackSummary,
                        totalStackDepth = status.totalStackDepth,
                        variables = status.variables,
                        sourceContext = status.sourceContext,
                        currentThread = status.currentThread,
                        threadCount = status.threadCount
                    ))
                }
                is WaitOutcome.SessionStopped -> {
                    createJsonResult(buildStoppedResult(session))
                }
                null -> {
                    createJsonResult(WaitForPauseResult(
                        waitResult = "timeout",
                        message = "No pause within ${timeoutSeconds}s",
                        sessionId = session.hashCode().toString(),
                        name = session.sessionName,
                        state = if (session.isStopped) "stopped" else if (session.isPaused) "paused" else "running"
                    ))
                }
            }
        } finally {
            session.removeSessionListener(listener)
        }
    }

    private fun handlePause(
        session: XDebugSession,
        breakpointIds: Set<String>?,
        deferred: CompletableDeferred<WaitOutcome>,
        startTime: Long,
        timeoutMs: Long
    ) {
        if (deferred.isCompleted) return

        if (breakpointIds == null) {
            deferred.complete(WaitOutcome.Paused)
            return
        }

        val hitInfo = SessionStatusCollector.getBreakpointHitInfo(session)

        if (hitInfo == null) {
            // Not a breakpoint pause (exception, manual, step) — always return
            deferred.complete(WaitOutcome.Paused)
            return
        }

        if (hitInfo.breakpointId in breakpointIds) {
            deferred.complete(WaitOutcome.Paused)
            return
        }

        // Non-matching breakpoint — auto-resume if time remains
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed >= timeoutMs) {
            deferred.complete(WaitOutcome.Paused)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (!session.isStopped && session.isPaused) {
                session.resume()
            }
        }
    }

    private fun buildStoppedResult(session: XDebugSession): WaitForPauseResult {
        return WaitForPauseResult(
            waitResult = "session_stopped",
            message = "Debug session ended while waiting",
            sessionId = session.hashCode().toString(),
            name = session.sessionName,
            state = "stopped"
        )
    }

    private sealed class WaitOutcome {
        data object Paused : WaitOutcome()
        data object SessionStopped : WaitOutcome()
    }
}
