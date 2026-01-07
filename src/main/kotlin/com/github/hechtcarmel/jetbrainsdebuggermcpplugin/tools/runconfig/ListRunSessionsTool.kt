package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunSessionInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunSessionListResult
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ListRunSessionsTool : AbstractMcpTool() {

    override val name = "list_run_sessions"

    override val description = """
        Lists all active run sessions with their IDs, names, and states.
        Returns both debug session IDs (use for get_run_log) and process handler IDs.
        Use to discover session IDs when multiple run sessions are running.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("List Run Sessions")

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
        val sessionInfos = mutableListOf<RunSessionInfo>()

        // 1. List debug sessions (these are what start_debug_session returns)
        val debuggerManager = XDebuggerManager.getInstance(project)
        val debugSessions = debuggerManager.getDebugSessions()

        for (session in debugSessions) {
            val debugProcess = session.debugProcess
            val processHandler = debugProcess?.processHandler

            val sessionId = session.hashCode().toString()
            val processId = processHandler?.hashCode()?.toString()
            val osProcessId = getOsProcessId(processHandler)

            val sessionName = try {
                session.javaClass.getMethod("getName").invoke(session) as? String
            } catch (e: Exception) {
                "Debug Session"
            }

            sessionInfos.add(RunSessionInfo(
                id = sessionId, // This is the ID to use with get_run_log
                name = "$sessionName (Debug Session)",
                state = if (session.isStopped) "stopped" else "running",
                processId = osProcessId,
                executorId = "debug",
                runConfigurationName = sessionName,
                additionalInfo = mapOf(
                    "processHandlerId" to processId,
                    "sessionType" to "debug"
                )
            ))
        }

        // 2. List run sessions (ExecutionManager)
        val executionManager = ExecutionManager.getInstance(project)
        val runningProcesses: Array<ProcessHandler> = executionManager.getRunningProcesses()

        for (processHandler in runningProcesses) {
            val executionId = getExecutionId(processHandler)
            val runConfiguration = getRunConfiguration(processHandler, executionManager)
            val osProcessId = getOsProcessId(processHandler)
            val name = runConfiguration?.name ?: processHandler.toString()
            val state = if (processHandler.isProcessTerminated) "stopped" else "running"

            sessionInfos.add(RunSessionInfo(
                id = processHandler.hashCode().toString(), // Use process handler ID for regular runs
                name = "$name (Run Session)",
                state = state,
                processId = osProcessId,
                executorId = executionId,
                runConfigurationName = runConfiguration?.name,
                additionalInfo = mapOf(
                    "sessionType" to "run"
                )
            ))
        }

        return createJsonResult(RunSessionListResult(
            sessions = sessionInfos,
            totalCount = sessionInfos.size
        ))
    }

    private fun getExecutionId(processHandler: ProcessHandler): String? {
        return try {
            val method = processHandler.javaClass.getMethod("getExecutionId")
            val result = method.invoke(processHandler)
            if (result is Long) result.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getOsProcessId(processHandler: ProcessHandler?): Long? {
        if (processHandler == null) return null
        return try {
            val processField = processHandler.javaClass.getDeclaredField("process")
            processField.isAccessible = true
            val process = processField.get(processHandler) as? Process
            process?.pid()
        } catch (e: Exception) {
            null
        }
    }

    private fun getRunConfiguration(
        processHandler: ProcessHandler,
        executionManager: ExecutionManager
    ): RunConfiguration? {
        return try {
            val method = processHandler.javaClass.getMethod("getRunConfiguration")
            method.invoke(processHandler) as? RunConfiguration
        } catch (e: Exception) {
            null
        }
    }
}