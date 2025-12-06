package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.*
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class JsonRpcHandler(
    private val toolRegistry: ToolRegistry
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false  // Don't serialize null values - important for MCP compatibility
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<JsonRpcHandler>()

        // Parameter names
        private const val PARAM_NAME = "name"
        private const val PARAM_ARGUMENTS = "arguments"
        private const val PARAM_PROJECT_PATH = "project_path"

        // Error messages
        private const val ERROR_NO_PROJECT_OPEN = "no_project_open"
        private const val ERROR_PROJECT_NOT_FOUND = "project_not_found"
        private const val ERROR_MULTIPLE_PROJECTS = "multiple_projects_open"
        private const val MSG_NO_PROJECT_OPEN = "No project is currently open in the IDE"
        private const val MSG_MULTIPLE_PROJECTS = "Multiple projects are open. Please specify the 'project_path' parameter."
    }

    suspend fun handleRequest(jsonString: String): String {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(jsonString)
        } catch (e: Exception) {
            LOG.warn("Failed to parse JSON-RPC request", e)
            return json.encodeToString(createParseErrorResponse())
        }

        val response = try {
            routeRequest(request)
        } catch (e: Exception) {
            LOG.error("Error processing request: ${request.method}", e)
            createInternalErrorResponse(request.id, e.message ?: "Unknown error")
        }

        return json.encodeToString(response)
    }

    private suspend fun routeRequest(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {
            JsonRpcMethods.INITIALIZE -> processInitialize(request)
            JsonRpcMethods.INITIALIZED -> processInitialized(request)
            JsonRpcMethods.TOOLS_LIST -> processToolsList(request)
            JsonRpcMethods.TOOLS_CALL -> processToolCall(request)
            JsonRpcMethods.PING -> processPing(request)
            else -> createMethodNotFoundResponse(request.id, request.method)
        }
    }

    private fun processInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = InitializeResult(
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION,
            serverInfo = ServerInfo(
                name = McpConstants.SERVER_NAME,
                version = McpConstants.SERVER_VERSION,
                description = McpConstants.SERVER_DESCRIPTION
            ),
            capabilities = ServerCapabilities(
                tools = ToolCapability(listChanged = false)
            )
        )

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private fun processInitialized(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private fun processToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = toolRegistry.getToolDefinitions()
        val result = ToolsListResult(tools = tools)

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private suspend fun processToolCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createInvalidParamsResponse(request.id, "Missing params")

        val toolName = params[PARAM_NAME]?.jsonPrimitive?.contentOrNull
            ?: return createInvalidParamsResponse(request.id, "Missing tool name")

        val arguments = params[PARAM_ARGUMENTS]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createMethodNotFoundResponse(request.id, "Tool not found: $toolName")

        // Extract optional project_path from arguments
        val projectPath = arguments[PARAM_PROJECT_PATH]?.jsonPrimitive?.contentOrNull

        val projectResult = resolveProject(projectPath)
        if (projectResult.isError) {
            return JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(projectResult.errorResult!!)
            )
        }

        val project = projectResult.project!!

        // Record command in history
        val commandEntry = CommandEntry(
            toolName = toolName,
            parameters = arguments
        )
        val historyService = CommandHistoryService.getInstance(project)
        historyService.recordCommand(commandEntry)

        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(project, arguments)
            val durationMs = System.currentTimeMillis() - startTime

            // Update command status
            historyService.updateCommandStatus(
                id = commandEntry.id,
                status = if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = result.content.firstOrNull()?.let {
                    when (it) {
                        is ContentBlock.Text -> it.text
                    }
                },
                durationMs = durationMs
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            LOG.error("Tool execution failed: $toolName", e)

            // Update command status with error
            historyService.updateCommandStatus(
                id = commandEntry.id,
                status = CommandStatus.ERROR,
                result = e.message ?: "Unknown error",
                durationMs = durationMs
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(
                    ToolCallResult(
                        content = listOf(ContentBlock.Text(text = e.message ?: "Unknown error")),
                        isError = true
                    )
                )
            )
        }
    }

    private fun processPing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private data class ProjectResolutionResult(
        val project: Project? = null,
        val errorResult: ToolCallResult? = null,
        val isError: Boolean = false
    )

    private fun resolveProject(projectPath: String?): ProjectResolutionResult {
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }

        // No projects open
        if (openProjects.isEmpty()) {
            return ProjectResolutionResult(
                isError = true,
                errorResult = ToolCallResult(
                    content = listOf(ContentBlock.Text(
                        text = json.encodeToString(buildJsonObject {
                            put("error", ERROR_NO_PROJECT_OPEN)
                            put("message", MSG_NO_PROJECT_OPEN)
                        })
                    )),
                    isError = true
                )
            )
        }

        // If project_path is provided, find matching project
        if (projectPath != null) {
            val matchingProject = openProjects.find { it.basePath == projectPath }
            return if (matchingProject != null) {
                ProjectResolutionResult(project = matchingProject)
            } else {
                ProjectResolutionResult(
                    isError = true,
                    errorResult = ToolCallResult(
                        content = listOf(ContentBlock.Text(
                            text = json.encodeToString(buildJsonObject {
                                put("error", ERROR_PROJECT_NOT_FOUND)
                                put("message", "Project not found: $projectPath")
                                put("available_projects", buildJsonArray {
                                    openProjects.forEach { proj ->
                                        add(buildJsonObject {
                                            put("name", proj.name)
                                            put("path", proj.basePath ?: "")
                                        })
                                    }
                                })
                            })
                        )),
                        isError = true
                    )
                )
            }
        }

        // Only one project open - use it
        if (openProjects.size == 1) {
            return ProjectResolutionResult(project = openProjects.first())
        }

        // Multiple projects open, no path specified - return error with list
        return ProjectResolutionResult(
            isError = true,
            errorResult = ToolCallResult(
                content = listOf(ContentBlock.Text(
                    text = json.encodeToString(buildJsonObject {
                        put("error", ERROR_MULTIPLE_PROJECTS)
                        put("message", MSG_MULTIPLE_PROJECTS)
                        put("available_projects", buildJsonArray {
                            openProjects.forEach { proj ->
                                add(buildJsonObject {
                                    put("name", proj.name)
                                    put("path", proj.basePath ?: "")
                                })
                            }
                        })
                    })
                )),
                isError = true
            )
        )
    }

    private fun createParseErrorResponse(): JsonRpcResponse {
        return JsonRpcResponse(
            error = JsonRpcError(
                code = JsonRpcErrorCodes.PARSE_ERROR,
                message = "Parse error"
            )
        )
    }

    private fun createInvalidParamsResponse(id: JsonElement?, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcErrorCodes.INVALID_PARAMS,
                message = message
            )
        )
    }

    private fun createMethodNotFoundResponse(id: JsonElement?, method: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
                message = "Method not found: $method"
            )
        )
    }

    private fun createInternalErrorResponse(id: JsonElement?, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcErrorCodes.INTERNAL_ERROR,
                message = message
            )
        )
    }
}
