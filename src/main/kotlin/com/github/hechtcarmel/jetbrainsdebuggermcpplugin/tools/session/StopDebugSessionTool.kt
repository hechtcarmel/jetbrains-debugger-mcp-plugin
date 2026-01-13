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
            ?: return createErrorResult("Missing required parameter: session_id")

        // First, try to resolve as a debug session
        val debugSession = getDebuggerManager(project).debugSessions.find {
            it.debugProcess.processHandler.hashCode().toString() == sessionId
        }
        if (debugSession != null) {
            val resolvedSessionId = debugSession.debugProcess.processHandler.hashCode().toString()
            val sessionName = debugSession.sessionName
            return try {
                debugSession.stop()
                createJsonResult(StopSessionResult(
                    sessionId = resolvedSessionId,
                    status = "stopped",
                    message = "Debug session '$sessionName' stopped"
                ))
            } catch (e: Exception) {
                createErrorResult("Failed to stop debug session: ${e.message}")
            }
        }

        // If not a debug session, try to resolve as a run session
        val processHandler = resolveRunSession(project, sessionId)
            ?: return createErrorResult("Session not found: $sessionId")

        val resolvedSessionId = processHandler.hashCode().toString()
        val sessionName = processHandler.toString()

        return try {
            if (processHandler.isProcessTerminated) {
                createJsonResult(StopSessionResult(
                    sessionId = resolvedSessionId,
                    status = "already_stopped",
                    message = "Run session '$sessionName' was already stopped"
                ))
            } else {
                processHandler.destroyProcess()
                createJsonResult(StopSessionResult(
                    sessionId = resolvedSessionId,
                    status = "stopped",
                    message = "Run session '$sessionName' stopped"
                ))
            }
        } catch (e: Exception) {
            createErrorResult("Failed to stop run session: ${e.message}")
        }
    }

    private fun resolveRunSession(project: Project, sessionId: String): com.intellij.execution.process.ProcessHandler? {
        val executionManager = com.intellij.execution.ExecutionManager.getInstance(project)
        return executionManager.getRunningProcesses().find {
            it.hashCode().toString() == sessionId
        }
    }
}
