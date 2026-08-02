package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.JsonObject

/**
 * A debugger capability exposed over MCP.
 *
 * Schemas stay raw JSON Schema [JsonObject]s rather than SDK `ToolSchema`s: that is the format
 * tools are readable in, and [toSdkTool] is the single place that translates it for the wire.
 * Results are SDK [CallToolResult]s, because maintaining a parallel response model the SDK would
 * only have to convert back is precisely the cost this migration removes.
 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val outputSchema: JsonObject?
        get() = null
    val annotations: ToolAnnotations

    suspend fun execute(project: Project, arguments: JsonObject): CallToolResult
}
