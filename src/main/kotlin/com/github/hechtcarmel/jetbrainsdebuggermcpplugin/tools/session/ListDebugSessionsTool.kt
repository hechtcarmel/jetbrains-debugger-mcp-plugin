package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SessionInfo
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
        val sessionInfos = mutableListOf<SessionInfo>()

        // 1. List debug sessions (from XDebuggerManager)
        val sessions = getAllSessions(project)
        val currentSession = getCurrentSession(project)

        sessions.forEach { session ->
            val processHandler = session.debugProcess.processHandler
            sessionInfos.add(SessionInfo(
                id = processHandler.hashCode().toString(),
                name = session.sessionName,
                type = "debug",
                state = getSessionState(session),
                isCurrent = session == currentSession,
                runConfigurationName = session.runContentDescriptor?.displayName,
                processId = getProcessId(processHandler)
            ))
        }

        // 2. List run sessions (from ExecutionManager)
        val executionManager = com.intellij.execution.ExecutionManager.getInstance(project)
        val runningProcesses = executionManager.getRunningProcesses()

        runningProcesses.forEach { processHandler ->
            // Avoid duplicating sessions that are already covered by the debugger
            val isDebugged = sessions.any { it.debugProcess.processHandler == processHandler }
            if (!isDebugged) {
                sessionInfos.add(SessionInfo(
                    id = processHandler.hashCode().toString(),
                    name = processHandler.toString(),
                    type = "run",
                    state = if (processHandler.isProcessTerminated) "stopped" else "running",
                    isCurrent = false, // 'run' sessions don't have a concept of 'current'
                    runConfigurationName = getRunConfigurationName(processHandler, executionManager),
                    processId = getProcessId(processHandler)
                ))
            }
        }

        return createJsonResult(DebugSessionListResult(
            sessions = sessionInfos,
            currentSessionId = currentSession?.debugProcess?.processHandler?.hashCode()?.toString(),
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

    private fun getRunConfigurationName(
        processHandler: com.intellij.execution.process.ProcessHandler,
        executionManager: com.intellij.execution.ExecutionManager
    ): String? {
        return try {
            // This is a bit of a hack, as there's no public API for this.
            // It might break in future versions of the IDE.
            val descriptors = executionManager.getRunningDescriptors { true }
            val descriptor = descriptors.find { it.processHandler == processHandler }
            descriptor?.displayName
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
data class DebugSessionListResult(
    val sessions: List<SessionInfo>,
    val currentSessionId: String?,
    val totalCount: Int
)
