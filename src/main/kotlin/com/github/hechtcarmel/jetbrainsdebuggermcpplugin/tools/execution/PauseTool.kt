package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pauses execution of a running debug session.
 */
class PauseTool : AbstractMcpTool() {

    override val name = "pause_execution"

    override val description = """
        Pauses a running debug session at its current execution point.
        Use when you need to inspect state during execution. After pausing, use get_debug_session_status to see the current location.
    """.trimIndent()

    override val annotations = ToolAnnotations.idempotentMutable("Pause Execution")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (session.isPaused) {
            return createErrorResult("Session is already paused")
        }

        return try {
            // pause must be called from EDT
            ApplicationManager.getApplication().invokeAndWait {
                session.pause()
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "pause",
                status = "success",
                message = "Execution paused",
                newState = "paused"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to pause: ${e.message}")
        }
    }
}
