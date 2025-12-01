package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.*
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.ProjectResolutionResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.mcpJson
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class JsonRpcHandler(
    private val toolRegistry: ToolRegistry,
    private val serverService: McpServerService
) {
    private val log = Logger.getInstance(JsonRpcHandler::class.java)

    suspend fun handleRequest(jsonString: String): String? {
        val request = try {
            mcpJson.decodeFromString<JsonRpcRequest>(jsonString)
        } catch (e: Exception) {
            log.warn("Failed to parse JSON-RPC request: ${e.message}")
            return mcpJson.encodeToString(createParseErrorResponse())
        }

        log.debug("Handling JSON-RPC request: method=${request.method}, id=${request.id}")

        // Handle notifications (no response expected)
        if (request.id == null) {
            handleNotification(request)
            return null
        }

        val response = routeRequest(request)
        return mcpJson.encodeToString(response)
    }

    private fun handleNotification(request: JsonRpcRequest) {
        when (request.method) {
            JsonRpcMethods.INITIALIZED -> {
                log.info("Client initialized notification received")
            }
            else -> {
                log.debug("Unknown notification: ${request.method}")
            }
        }
    }

    private suspend fun routeRequest(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {
            JsonRpcMethods.INITIALIZE -> processInitialize(request)
            JsonRpcMethods.TOOLS_LIST -> processToolsList(request)
            JsonRpcMethods.TOOLS_CALL -> processToolCall(request)
            JsonRpcMethods.PING -> processPing(request)
            else -> createMethodNotFoundResponse(request.id, request.method)
        }
    }

    private fun processInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = InitializeResult(
            serverInfo = serverService.getServerInfo()
        )
        return JsonRpcResponse(
            id = request.id,
            result = mcpJson.encodeToJsonElement(result)
        )
    }

    private fun processToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val result = ToolsListResult(
            tools = toolRegistry.getToolDefinitions()
        )
        return JsonRpcResponse(
            id = request.id,
            result = mcpJson.encodeToJsonElement(result)
        )
    }

    private suspend fun processToolCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createInvalidParamsResponse(request.id, "Missing params")

        val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return createInvalidParamsResponse(request.id, "Missing tool name")

        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createToolNotFoundResponse(request.id, toolName)

        // Resolve project from arguments
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.contentOrNull
        val projectResult = ProjectUtils.resolveProject(projectPath)

        val project = when (projectResult) {
            is ProjectResolutionResult.Success -> projectResult.project
            is ProjectResolutionResult.MultipleProjects -> {
                return createMultipleProjectsErrorResponse(request.id, projectResult.projects)
            }
            is ProjectResolutionResult.NotFound -> {
                return createProjectNotFoundResponse(request.id, projectResult.requestedPath)
            }
            is ProjectResolutionResult.NoProjectsOpen -> {
                return createNoProjectsErrorResponse(request.id)
            }
        }

        // Execute tool
        return try {
            val result = tool.execute(project, arguments)
            JsonRpcResponse(
                id = request.id,
                result = mcpJson.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            log.error("Tool execution failed: ${tool.name}", e)
            createErrorResponse(
                request.id,
                JsonRpcErrorCodes.INTERNAL_ERROR,
                "Tool execution failed: ${e.message}"
            )
        }
    }

    private fun processPing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    // Error response builders

    private fun createParseErrorResponse(): JsonRpcResponse = JsonRpcResponse(
        error = JsonRpcError(
            code = JsonRpcErrorCodes.PARSE_ERROR,
            message = "Parse error"
        )
    )

    private fun createMethodNotFoundResponse(id: JsonElement?, method: String): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
            message = "Method not found: $method"
        )
    )

    private fun createInvalidParamsResponse(id: JsonElement?, message: String): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = JsonRpcErrorCodes.INVALID_PARAMS,
            message = message
        )
    )

    private fun createToolNotFoundResponse(id: JsonElement?, toolName: String): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
            message = "Tool not found: $toolName"
        )
    )

    private fun createMultipleProjectsErrorResponse(
        id: JsonElement?,
        projects: List<com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.ProjectInfo>
    ): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = JsonRpcErrorCodes.MULTIPLE_PROJECTS,
            message = "Multiple projects are open. Please specify 'projectPath' parameter.",
            data = mcpJson.encodeToJsonElement(mapOf("open_projects" to projects))
        )
    )

    private fun createProjectNotFoundResponse(id: JsonElement?, path: String): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = JsonRpcErrorCodes.PROJECT_NOT_FOUND,
            message = "Project not found: $path"
        )
    )

    private fun createNoProjectsErrorResponse(id: JsonElement?): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = JsonRpcErrorCodes.PROJECT_NOT_FOUND,
            message = "No projects are open"
        )
    )

    private fun createErrorResponse(id: JsonElement?, code: Int, message: String): JsonRpcResponse = JsonRpcResponse(
        id = id,
        error = JsonRpcError(
            code = code,
            message = message
        )
    )
}
