package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.McpTool
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Everything that happens around a tool call but is not the tool itself: modality, project
 * resolution, command-history recording, and turning a thrown exception into a readable result.
 *
 * This is the single handler the SDK invokes per tool, and the only seam where plugin concerns meet
 * the protocol. It runs strictly in the order below, which is what keeps the history invariants
 * true: the SDK resolves the tool name *before* calling us, so `initialize`, `tools/list`, `ping`
 * and calls naming an unknown tool never reach here and are correctly absent from history.
 */
class McpToolBridge {

    private companion object {
        private val LOG = logger<McpToolBridge>()
    }

    suspend fun invoke(tool: McpTool, request: CallToolRequest): CallToolResult =
        runWithIdeModality {
            val arguments = request.arguments ?: JsonObject(emptyMap())

            when (val resolution = McpProjectResolver.resolve(arguments)) {
                is McpProjectResolver.Resolution.Failed ->
                    CallToolResult(content = listOf(TextContent(resolution.payload)), isError = true)

                is McpProjectResolver.Resolution.Resolved ->
                    execute(tool, resolution.project, arguments)
            }
        }

    private suspend fun execute(tool: McpTool, project: Project, arguments: JsonObject): CallToolResult {
        val history = CommandHistoryService.getInstance(project)
        val entry = CommandEntry(toolName = tool.name, parameters = arguments)
        history.recordCommand(entry)

        val startedAt = System.currentTimeMillis()
        return try {
            val result = tool.execute(project, arguments)
            history.updateCommandStatus(
                id = entry.id,
                status = if (result.isError == true) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = (result.content.firstOrNull() as? TextContent)?.text,
                durationMs = System.currentTimeMillis() - startedAt,
            )
            result
        } catch (e: CancellationException) {
            // Cancellation is the caller going away, not a tool failure — never report it as one.
            history.updateCommandStatus(
                id = entry.id,
                status = CommandStatus.ERROR,
                result = "Cancelled",
                durationMs = System.currentTimeMillis() - startedAt,
            )
            throw e
        } catch (e: Exception) {
            LOG.error("Tool execution failed: ${tool.name}", e)
            history.updateCommandStatus(
                id = entry.id,
                status = CommandStatus.ERROR,
                result = e.message ?: "Unknown error",
                durationMs = System.currentTimeMillis() - startedAt,
            )
            CallToolResult(
                content = listOf(TextContent(e.message ?: "Unknown error")),
                isError = true,
            )
        }
    }
}
