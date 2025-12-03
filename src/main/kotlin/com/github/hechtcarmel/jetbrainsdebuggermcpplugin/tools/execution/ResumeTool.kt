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
 * Resumes execution of a paused debug session.
 */
class ResumeTool : AbstractMcpTool() {

    override val name = "resume_execution"

    override val description = """
        Resumes program execution from a paused state.
        Execution continues until the next breakpoint, exception, or program completion. Use get_debug_session_status afterward to see where execution stopped.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Resume Execution")

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
            return createErrorResult("Session is not paused")
        }

        return try {
            // resume must be called from EDT
            ApplicationManager.getApplication().invokeAndWait {
                session.resume()
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "resume",
                status = "success",
                message = "Execution resumed",
                newState = "running"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to resume: ${e.message}")
        }
    }
}
