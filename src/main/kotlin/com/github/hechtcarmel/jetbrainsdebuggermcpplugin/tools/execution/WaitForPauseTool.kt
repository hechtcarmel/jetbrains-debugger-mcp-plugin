package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.WaitForPauseResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.putSessionStatusProperties
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.SessionStatusCollector
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        If session_id is omitted and no session exists yet (e.g., right after start_debug_session), the tool will wait for a session to appear before waiting for it to pause. This means you can call start_debug_session followed by wait_for_pause without needing to poll for the session ID.
        Optionally filter by breakpoint_ids to only return when specific breakpoints are hit — non-matching breakpoint pauses are auto-resumed.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Wait For Pause")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("waitResult") { put("type", "string"); put("description", "Why the wait completed: 'paused', 'timeout', or 'session_stopped'") }
            putJsonObject("message") { put("type", "string"); put("description", "Human-readable description of the wait outcome") }
            putJsonObject("sessionId") { put("type", "string"); put("description", "Debug session ID") }
            putJsonObject("name") { put("type", "string"); put("description", "Debug session display name") }
            putJsonObject("state") { put("type", "string"); put("description", "Session state: 'running', 'paused', or 'stopped'") }
            putSessionStatusProperties()
        }
        put("required", buildJsonArray { add(JsonPrimitive("waitResult")); add(JsonPrimitive("message")); add(JsonPrimitive("sessionId")); add(JsonPrimitive("name")); add(JsonPrimitive("state")) })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("session_id", stringProperty("Debug session ID. If omitted, uses the current session. If no session exists yet, waits for one to appear (useful right after start_debug_session)."))
            put("timeout", integerProperty("Maximum wait time in seconds. Must be positive.", minimum = 1))
            putJsonObject("breakpoint_ids") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "If set, only complete when one of these breakpoints is hit. Non-matching breakpoint pauses are auto-resumed. Pauses where no breakpoint is detected at the current location (e.g., exceptions, manual pauses) return immediately. Note: detection uses file/line heuristics and may not distinguish all pause causes perfectly.")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("timeout"))
        })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val timeoutSeconds = ToolArguments.requireInt(arguments, "timeout")
        val breakpointIds = ToolArguments.optionalStringList(arguments, "breakpoint_ids")?.toSet()

        if (timeoutSeconds < 1) {
            return createErrorResult("timeout must be positive")
        }

        val session = resolveSession(project, sessionId)
            ?: if (sessionId != null) {
                return createErrorResult("Session not found: $sessionId")
            } else {
                // No session yet — wait for one to appear (e.g., after start_debug_session)
                awaitSession(project, timeoutSeconds * 1000L)
                    ?: return createErrorResult("No debug session appeared within ${timeoutSeconds}s")
            }

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
                        sessionId = getSessionId(session),
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

        // ModalityState.any(): with the default modality this auto-resume queues until the user
        // closes whatever modal dialog happens to be open, leaving the session paused indefinitely.
        ApplicationManager.getApplication().invokeLater({
            if (!session.isStopped && session.isPaused) {
                session.resume()
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }

    private fun buildStoppedResult(session: XDebugSession): WaitForPauseResult {
        return WaitForPauseResult(
            waitResult = "session_stopped",
            message = "Debug session ended while waiting",
            sessionId = getSessionId(session),
            name = session.sessionName,
            state = "stopped"
        )
    }

    /**
     * Waits for a debug session to appear in the project.
     * Polls every 1 second, up to the given timeout.
     */
    private suspend fun awaitSession(project: Project, timeoutMs: Long): XDebugSession? {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val session = getCurrentSession(project)
                if (session != null) return@withTimeoutOrNull session
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    private sealed class WaitOutcome {
        data object Paused : WaitOutcome()
        data object SessionStopped : WaitOutcome()
    }
}
