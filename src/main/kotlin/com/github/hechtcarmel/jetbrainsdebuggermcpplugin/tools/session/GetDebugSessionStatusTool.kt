package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.putSessionStatusProperties
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.SessionStatusCollector
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gets comprehensive status of a debug session.
 *
 * This is the primary tool for understanding debug state in a single call.
 * Returns variables, stack summary, source context, and more.
 */
class GetDebugSessionStatusTool : AbstractMcpTool() {

    override val name = "get_debug_session_status"

    override val description = """
        Returns the complete current state of a debug session: execution location, variables, stack trace, and surrounding source code.
        This is the primary tool for understanding where execution stopped and why. Use after any execution control operation (resume, step, pause) to see the result.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Get Debug Status")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string"); put("description", "Unique identifier for the debug session") }
            putJsonObject("name") { put("type", "string"); put("description", "Display name of the debug session") }
            putJsonObject("state") { put("type", "string"); put("description", "Current state: 'running', 'paused', or 'stopped'") }
            putSessionStatusProperties()
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")); add(JsonPrimitive("name")); add(JsonPrimitive("state")) })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("include_variables", booleanProperty("Include variables from current frame", default = true))
            put("include_source_context", booleanProperty("Include source code around current line", default = true))
            put("source_context_lines", integerProperty("Lines of context above/below current line", default = 5, minimum = 0, maximum = 50))
            put("max_stack_frames", integerProperty("Maximum stack frames in summary", default = 10, minimum = 1, maximum = 200))
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val includeVariables = ToolArguments.optionalBoolean(arguments, "include_variables", default = true)
        val includeSourceContext = ToolArguments.optionalBoolean(arguments, "include_source_context", default = true)
        val sourceContextLines = ToolArguments.optionalInt(arguments, "source_context_lines", default = 5, min = 0, max = 50)
        val maxStackFrames = ToolArguments.optionalInt(arguments, "max_stack_frames", default = 10, min = 1, max = 200)

        val session = requireSession(project, sessionId)

        val status = SessionStatusCollector.collectStatus(
            project = project,
            session = session,
            includeVariables = includeVariables,
            includeSourceContext = includeSourceContext,
            sourceContextLines = sourceContextLines,
            maxStackFrames = maxStackFrames
        )

        return createJsonResult(status)
    }
}
