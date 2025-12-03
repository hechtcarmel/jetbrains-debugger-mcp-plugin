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
 * Steps over the current line (executes it without entering functions).
 */
class StepOverTool : AbstractMcpTool() {

    override val name = "step_over"

    override val description = """
        Executes the current line and stops at the next line, without entering function calls.
        Use for line-by-line debugging when you don't need to inspect function internals. Check get_debug_session_status for the new location.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Step Over")

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

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to step over")
        }

        return try {
            // stepOver must be called from EDT
            ApplicationManager.getApplication().invokeAndWait {
                session.stepOver(false)
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "step_over",
                status = "success",
                message = "Stepped over",
                newState = "running" // Will pause again after step completes
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to step over: ${e.message}")
        }
    }
}
