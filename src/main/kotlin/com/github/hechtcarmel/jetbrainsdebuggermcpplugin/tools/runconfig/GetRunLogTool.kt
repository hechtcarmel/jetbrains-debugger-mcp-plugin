package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunLogResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.ProcessLogManager
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GetRunLogTool : AbstractMcpTool() {

    override val name = "get_run_log"
    override val description = "Retrieves console output from a run session."
    override val annotations = ToolAnnotations.readOnly("Get Run Log")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("lines", integerProperty("Number of lines to return", 100))
        }
        putJsonArray("required") { add(JsonPrimitive("session_id")) }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
            ?: return createErrorResult("session_id is required")
        val lines = arguments["lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100

        // 1. Resolve the process
        val processHandler = resolveRunSession(project, sessionId)
            ?: return createErrorResult("Run session not found: $sessionId")

        // 2. Ensure we are tracking it (legacy fallback)
        if (!ProcessLogManager.hasListener(processHandler.hashCode())) {
            ProcessLogManager.attachListener(processHandler)
        }

        return try {
            // 3. Get log content safely (Must be run on UI thread to access ConsoleView document)
            var logOutput = ""
            ApplicationManager.getApplication().invokeAndWait {
                logOutput = ProcessLogManager.getLogContent(project, processHandler)
            }

            // 4. Process lines
            val allLines = logOutput.lines()
            val totalLines = allLines.size
            val startIndex = maxOf(0, totalLines - lines)
            val returnedLines = allLines.drop(startIndex)

            createJsonResult(RunLogResult(
                sessionId = sessionId,
                log = returnedLines.joinToString("\n"),
                totalLines = totalLines,
                returnedLines = returnedLines.size
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to retrieve run log: ${e.message}")
        }
    }

    private fun resolveRunSession(project: Project, sessionId: String): ProcessHandler? {
        val executionManager = ExecutionManager.getInstance(project)
        executionManager.getRunningProcesses().forEach {
            if (isMatch(it, sessionId)) return it
        }

        val debuggerManager = XDebuggerManager.getInstance(project)
        debuggerManager.debugSessions.forEach {
            val ph = it.debugProcess.processHandler
            if (it.hashCode().toString() == sessionId) return ph
            if (isMatch(ph, sessionId)) return ph
        }
        return null
    }

    private fun isMatch(handler: ProcessHandler, sessionId: String): Boolean {
        if (handler.hashCode().toString() == sessionId) return true
        // Try matching by OS Process ID if available
        return try {
            val process = handler.javaClass.getMethod("getProcess").invoke(handler) as? Process
            process?.pid()?.toString() == sessionId
        } catch (e: Exception) { false }
    }
}