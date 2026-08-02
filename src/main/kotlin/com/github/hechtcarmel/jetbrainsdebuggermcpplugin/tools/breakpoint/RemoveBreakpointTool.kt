package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RemoveBreakpointResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StableObjectIds
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    override val annotations = ToolAnnotationPresets.idempotentMutable("Remove Breakpoint", destructive = true)

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("breakpoint_id", stringProperty("ID of the breakpoint to remove"))
        }
        putJsonArray("required") {
            add(JsonPrimitive("breakpoint_id"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val breakpointId = ToolArguments.requireString(arguments, "breakpoint_id")

        val breakpointManager = getDebuggerManager(project).breakpointManager
        val allBreakpoints = breakpointManager.allBreakpoints

        val breakpoint = allBreakpoints.find { StableObjectIds.idFor(it) == breakpointId }
            ?: return createErrorResult("Breakpoint not found: $breakpointId")

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.run<RuntimeException> {
                    breakpointManager.removeBreakpoint(breakpoint)
                }
            }

            createJsonResult(RemoveBreakpointResult(
                breakpointId = breakpointId,
                status = "removed",
                message = "Breakpoint removed successfully"
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to remove breakpoint: ${e.message}")
        }
    }
}
