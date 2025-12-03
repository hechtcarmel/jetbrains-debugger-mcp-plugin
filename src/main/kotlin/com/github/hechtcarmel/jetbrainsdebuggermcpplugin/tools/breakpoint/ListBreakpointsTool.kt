package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.BreakpointInfo
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lists all breakpoints in the project.
 */
class ListBreakpointsTool : AbstractMcpTool() {

    override val name = "list_breakpoints"

    override val description = """
        Lists all breakpoints in the project with their locations, conditions, and states.
        Use to discover breakpoint IDs for removal, or to verify breakpoints are configured correctly before debugging.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("List Breakpoints")

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
        val breakpointManager = getDebuggerManager(project).breakpointManager
        val allBreakpoints = breakpointManager.allBreakpoints

        val breakpointInfos = allBreakpoints.map { breakpoint ->
            createBreakpointInfo(breakpoint)
        }

        return createJsonResult(BreakpointListResult(
            breakpoints = breakpointInfos,
            totalCount = breakpointInfos.size,
            enabledCount = breakpointInfos.count { it.enabled }
        ))
    }

    private fun createBreakpointInfo(breakpoint: XBreakpoint<*>): BreakpointInfo {
        val isLineBreakpoint = breakpoint is XLineBreakpoint<*>
        val lineBreakpoint = breakpoint as? XLineBreakpoint<*>

        return BreakpointInfo(
            id = breakpoint.hashCode().toString(),
            type = when {
                isLineBreakpoint -> "line"
                breakpoint.type.id.contains("exception", ignoreCase = true) -> "exception"
                else -> breakpoint.type.id
            },
            file = lineBreakpoint?.fileUrl?.removePrefix("file://"),
            line = lineBreakpoint?.let { it.line + 1 }, // Convert to 1-based
            enabled = breakpoint.isEnabled,
            condition = breakpoint.conditionExpression?.expression,
            logMessage = breakpoint.logExpressionObject?.expression,
            suspendPolicy = breakpoint.suspendPolicy?.name?.lowercase(),
            hitCount = 0, // Not directly available
            temporary = lineBreakpoint?.isTemporary ?: false,
            exceptionClass = if (!isLineBreakpoint) breakpoint.type.id else null,
            caught = null,
            uncaught = null
        )
    }
}

@Serializable
data class BreakpointListResult(
    val breakpoints: List<BreakpointInfo>,
    val totalCount: Int,
    val enabledCount: Int
)
