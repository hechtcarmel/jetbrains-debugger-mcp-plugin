package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolDefinition
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry {
    private val tools = ConcurrentHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun unregister(toolName: String) {
        tools.remove(toolName)
    }

    fun getTool(name: String): McpTool? = tools[name]

    fun getAllTools(): List<McpTool> = tools.values.toList()

    fun getToolDefinitions(): List<ToolDefinition> = tools.values.map { tool ->
        ToolDefinition(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema
        )
    }

    fun getToolCount(): Int = tools.size

    fun registerBuiltInTools() {
        // Tools will be registered here in Phase 4
        // For now, this is a placeholder
    }
}
