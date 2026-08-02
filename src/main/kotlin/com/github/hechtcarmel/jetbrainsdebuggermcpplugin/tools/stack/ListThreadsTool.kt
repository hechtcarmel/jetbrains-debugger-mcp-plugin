package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ThreadInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ThreadListResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ExecutionStackUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ListThreadsTool : AbstractMcpTool() {

    override val name = "list_threads"

    override val description = """
        Lists all threads in the debugged process with their states.
        Use in multi-threaded applications to see which threads exist and which is currently selected.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("List Threads")

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

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")

        val session = requirePausedSession(project, sessionId, "list threads")

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val threads = ExecutionStackUtils.collectExecutionStacks(suspendContext)
        val activeStack = suspendContext.activeExecutionStack

        val threadInfos = threads.map { stack ->
            ThreadInfo(
                id = stack.hashCode().toString(),
                name = stack.displayName,
                state = if (stack == activeStack) "paused" else "suspended",
                isCurrent = stack == activeStack
            )
        }

        return createJsonResult(ThreadListResult(
            sessionId = getSessionId(session),
            threads = threadInfos,
            currentThreadId = activeStack?.hashCode()?.toString()
        ))
    }
}
