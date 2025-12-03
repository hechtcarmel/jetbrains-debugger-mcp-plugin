package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StopSessionResult
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Stops an active debug session.
 */
class StopDebugSessionTool : AbstractMcpTool() {

    override val name = "stop_debug_session"

    override val description = """
        Terminates a debug session, stopping the debugged process.
        Use to end a debugging session. This is a destructive operation that cannot be undone.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Stop Debug Session", destructive = true)

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

        val resolvedSessionId = getSessionId(session)
        val sessionName = session.sessionName

        return try {
            session.stop()
            createJsonResult(StopSessionResult(
                sessionId = resolvedSessionId,
                status = "stopped",
                message = "Debug session '$sessionName' stopped"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to stop session: ${e.message}")
        }
    }
}
