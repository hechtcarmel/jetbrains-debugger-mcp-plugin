package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RemoveBreakpointResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Removes a breakpoint by ID.
 */
class RemoveBreakpointTool : AbstractMcpTool() {

    override val name = "remove_breakpoint"

    override val description = """
        Removes a breakpoint by its ID.
        Use list_breakpoints first to find the breakpoint ID. This operation is idempotent.
    """.trimIndent()

    override val annotations = ToolAnnotations.idempotentMutable("Remove Breakpoint", destructive = true)

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("breakpoint_id") {
                put("type", "string")
                put("description", "ID of the breakpoint to remove")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("breakpoint_id"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val breakpointId = arguments["breakpoint_id"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: breakpoint_id")

        val breakpointManager = getDebuggerManager(project).breakpointManager
        val allBreakpoints = breakpointManager.allBreakpoints

        val breakpoint = allBreakpoints.find { it.hashCode().toString() == breakpointId }
            ?: return createErrorResult("Breakpoint not found: $breakpointId")

        return try {
            withContext(Dispatchers.Main) {
                ApplicationManager.getApplication().runWriteAction {
                    breakpointManager.removeBreakpoint(breakpoint)
                }
            }

            createJsonResult(RemoveBreakpointResult(
                breakpointId = breakpointId,
                status = "removed",
                message = "Breakpoint removed successfully"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to remove breakpoint: ${e.message}")
        }
    }
}
