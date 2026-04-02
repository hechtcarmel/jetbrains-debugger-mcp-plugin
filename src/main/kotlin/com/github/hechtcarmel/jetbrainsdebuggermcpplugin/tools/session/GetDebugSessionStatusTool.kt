package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.SessionStatusCollector
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    override val annotations = ToolAnnotations.readOnly("Get Debug Status")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string"); put("description", "Unique identifier for the debug session") }
            putJsonObject("name") { put("type", "string"); put("description", "Display name of the debug session") }
            putJsonObject("state") { put("type", "string"); put("description", "Current state: 'running', 'paused', or 'stopped'") }
            putJsonObject("pausedReason") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Why execution paused: 'breakpoint', 'step', 'exception', or 'pause'") }
            putJsonObject("currentLocation") {
                putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
                putJsonObject("properties") {
                    putJsonObject("file") { put("type", "string") }
                    putJsonObject("line") { put("type", "integer") }
                    putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                    putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                }
                put("description", "Current execution location")
            }
            putJsonObject("variables") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("value") { put("type", "string") }
                        putJsonObject("type") { put("type", "string") }
                        putJsonObject("hasChildren") { put("type", "boolean") }
                    }
                }
                put("description", "Variables visible in current stack frame")
            }
            putJsonObject("stackSummary") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("index") { put("type", "integer") }
                        putJsonObject("file") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                        putJsonObject("line") { putJsonArray("type") { add(JsonPrimitive("integer")); add(JsonPrimitive("null")) } }
                        putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                        putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                    }
                }
                put("description", "Stack trace summary")
            }
            putJsonObject("sourceContext") {
                putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
                put("description", "Source code around the current execution point")
            }
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
            putJsonObject("include_variables") {
                put("type", "boolean")
                put("description", "Include variables from current frame")
                put("default", true)
            }
            putJsonObject("include_source_context") {
                put("type", "boolean")
                put("description", "Include source code around current line")
                put("default", true)
            }
            putJsonObject("source_context_lines") {
                put("type", "integer")
                put("description", "Lines of context above/below current line")
                put("default", 5)
                put("minimum", 0)
                put("maximum", 50)
            }
            putJsonObject("max_stack_frames") {
                put("type", "integer")
                put("description", "Maximum stack frames in summary")
                put("default", 10)
                put("minimum", 1)
                put("maximum", 200)
            }
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val includeVariables = arguments["include_variables"]?.jsonPrimitive?.booleanOrNull ?: true
        val includeSourceContext = arguments["include_source_context"]?.jsonPrimitive?.booleanOrNull ?: true
        val sourceContextLines = arguments["source_context_lines"]?.jsonPrimitive?.intOrNull ?: 5
        val maxStackFrames = arguments["max_stack_frames"]?.jsonPrimitive?.intOrNull ?: 10

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

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
