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
 * Steps into the function call on the current line.
 */
class StepIntoTool : AbstractMcpTool() {

    override val name = "step_into"

    override val description = """
        Steps into the function call on the current line, entering the function body.
        Use when you need to debug inside a function. If no function call exists on the current line, behaves like step_over.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Step Into")

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
            return createErrorResult("Session must be paused to step into")
        }

        return try {
            // stepInto must be called from EDT
            ApplicationManager.getApplication().invokeAndWait {
                session.stepInto()
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "step_into",
                status = "success",
                message = "Stepped into",
                newState = "running" // Will pause again after step completes
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to step into: ${e.message}")
        }
    }
}
