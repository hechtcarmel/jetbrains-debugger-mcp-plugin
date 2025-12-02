package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class RunToLineTool : AbstractMcpTool() {

    override val name = "run_to_line"

    override val description = """
        Runs execution until it reaches the specified line in the specified file.
        The debugger continues execution and pauses when the target line is reached.
        Similar to setting a temporary breakpoint and resuming.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the source file")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "Line number to run to (1-based)")
                put("minimum", 1)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val filePath = arguments["file_path"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file_path")
        val line = arguments["line"]?.jsonPrimitive?.intOrNull
            ?: return createErrorResult("Missing required parameter: line")

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to run to line")
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return createErrorResult("File not found: $filePath")

        val position = XDebuggerUtil.getInstance().createPosition(virtualFile, line - 1)
            ?: return createErrorResult("Cannot create position for $filePath:$line")

        session.runToPosition(position, false)

        return createJsonResult(ExecutionControlResult(
            sessionId = getSessionId(session),
            action = "run_to_line",
            status = "executed",
            message = "Running to $filePath:$line"
        ))
    }
}
