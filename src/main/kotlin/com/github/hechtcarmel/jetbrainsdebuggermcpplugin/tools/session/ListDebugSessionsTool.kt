package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionInfo
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lists all active debug sessions in the project.
 */
class ListDebugSessionsTool : AbstractMcpTool() {

    override val name = "list_debug_sessions"

    override val description = """
        Lists all active debug sessions with their IDs, names, and states.
        Use to discover session IDs when multiple debug sessions are running, or to check which session is current.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("List Debug Sessions")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessions = getAllSessions(project)
        val currentSession = getCurrentSession(project)

        val sessionInfos = sessions.map { session ->
            DebugSessionInfo(
                id = getSessionId(session),
                name = session.sessionName,
                state = getSessionState(session),
                isCurrent = session == currentSession,
                runConfigurationName = session.runContentDescriptor?.displayName,
                processId = session.debugProcess?.processHandler?.let { getProcessId(it) }
            )
        }

        return createJsonResult(DebugSessionListResult(
            sessions = sessionInfos,
            currentSessionId = currentSession?.let { getSessionId(it) },
            totalCount = sessionInfos.size
        ))
    }

    private fun getSessionState(session: XDebugSession): String {
        return when {
            session.isStopped -> "stopped"
            session.isPaused -> "paused"
            else -> "running"
        }
    }

    private fun getProcessId(processHandler: com.intellij.execution.process.ProcessHandler): Long? {
        return try {
            val process = processHandler.javaClass.getMethod("getProcess").invoke(processHandler)
            if (process is Process) {
                process.pid()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
data class DebugSessionListResult(
    val sessions: List<DebugSessionInfo>,
    val currentSessionId: String?,
    val totalCount: Int
)
