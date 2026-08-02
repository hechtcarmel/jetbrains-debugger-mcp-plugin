package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerUtil
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class RunToLineTool : AbstractMcpTool() {

    override val name = "run_to_line"

    override val description = """
        Continues execution until reaching a specific line in a file.
        Use as a shortcut instead of setting a temporary breakpoint. Execution may stop earlier if another breakpoint is hit.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Run to Line")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("file_path", stringProperty("Absolute path to the source file. Files inside JAR/ZIP archives are supported with the '!/' separator, e.g. '/path/to/lib-sources.jar!/com/example/Foo.kt' (the IDE's 'Copy Absolute Path' format for library sources)."))
            put("line", integerProperty("Target line number (1-based). Execution will pause when this line is about to execute. The line must be reachable from the current execution path.", minimum = 1))
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val filePath = ToolArguments.requireString(arguments, "file_path")
        val line = ToolArguments.requireInt(arguments, "line", min = 1)

        val session = requirePausedSession(project, sessionId, "run to line")

        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult(
                "File not found: $filePath. " +
                    "For files inside JAR archives, use the '!/' separator " +
                    "(e.g. /path/to/lib-sources.jar!/com/example/Foo.kt)."
            )

        val position = XDebuggerUtil.getInstance().createPosition(virtualFile, line - 1)
            ?: return createErrorResult("Cannot create position for $filePath:$line")

        return try {
            // runToPosition must be called from EDT
            onEdt {
                session.runToPosition(position, false)
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "run_to_line",
                status = "success",
                newState = "running",
                message = "Running to $filePath:$line"
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to run to line: ${e.message}")
        }
    }
}
