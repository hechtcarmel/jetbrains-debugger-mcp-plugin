package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariablesResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.FrameVariablesCollector
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XStackFrame
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gets variables from the current stack frame.
 */
class GetVariablesTool : AbstractMcpTool() {

    override val name = "get_variables"

    override val description = """
        Returns all variables visible in the current (or specified) stack frame.
        Use to inspect local variables, parameters, and accessible fields. For complex objects, use evaluate_expression to see their contents.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Get Variables")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string"); put("description", "Debug session ID") }
            putJsonObject("frameIndex") { put("type", "integer"); put("description", "Stack frame index that variables were retrieved from") }
            putJsonObject("variables") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string"); put("description", "Variable name") }
                        putJsonObject("value") { put("type", "string"); put("description", "String representation of the value") }
                        putJsonObject("type") { put("type", "string"); put("description", "Variable type name") }
                        putJsonObject("hasChildren") { put("type", "boolean"); put("description", "True if this variable has child properties") }
                    }
                    put("required", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("value")); add(JsonPrimitive("type")); add(JsonPrimitive("hasChildren")) })
                }
                put("description", "List of variables visible in the stack frame")
            }
            putJsonObject("scope") {
                putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }
                put("description", "Scope filter that produced this list, if one was requested")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")); add(JsonPrimitive("frameIndex")); add(JsonPrimitive("variables")) })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("frame_index", integerProperty("Stack frame index (0 = current frame)", default = 0, minimum = 0))
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val frameIndex = ToolArguments.optionalInt(arguments, "frame_index", default = 0, min = 0)

        val session = requirePausedSession(project, sessionId, "get variables")

        val frame = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            StackFrameUtils.getFrameAtIndex(session, frameIndex)
        } ?: return createErrorResult("No stack frame available at index $frameIndex")

        val variables = getVariablesFromFrame(frame)

        return createJsonResult(VariablesResult(
            sessionId = getSessionId(session),
            frameIndex = frameIndex,
            variables = variables
        ))
    }

    private suspend fun getVariablesFromFrame(frame: XStackFrame): List<VariableInfo> {
        return FrameVariablesCollector.collectVariables(frame)
    }
}
