package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.intellij.openapi.application.ApplicationManager
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
        Continues execution until reaching a specific line in a file.
        Use as a shortcut instead of setting a temporary breakpoint. Execution may stop earlier if another breakpoint is hit.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Run to Line")

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
                put("description", "Target line number (1-based). Execution will pause when this line is about to execute. The line must be reachable from the current execution path.")
                put("minimum", 1)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
        put("additionalProperties", false)
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

        return try {
            // runToPosition must be called from EDT
            ApplicationManager.getApplication().invokeAndWait {
                session.runToPosition(position, false)
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "run_to_line",
                status = "success",
                newState = "running",
                message = "Running to $filePath:$line"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to run to line: ${e.message}")
        }
    }
}
