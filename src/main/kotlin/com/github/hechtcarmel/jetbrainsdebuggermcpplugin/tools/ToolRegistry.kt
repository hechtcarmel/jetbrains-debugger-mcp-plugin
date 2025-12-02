package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.ListBreakpointsTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.RemoveBreakpointTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.SetBreakpointTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.SetExceptionBreakpointTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.PauseTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.ResumeTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.RunToLineTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.StepIntoTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.StepOutTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.StepOverTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.navigation.GetSourceContextTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig.ListRunConfigurationsTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig.RunConfigurationTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.GetDebugSessionStatusTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.ListDebugSessionsTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.StartDebugSessionTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.StopDebugSessionTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack.GetStackTraceTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack.ListThreadsTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack.SelectStackFrameTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable.ExpandVariableTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable.GetVariablesTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable.SetVariableTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.watch.AddWatchTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.watch.RemoveWatchTool
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
        // Run Configuration Tools
        register(ListRunConfigurationsTool())
        register(RunConfigurationTool())

        // Debug Session Tools
        register(ListDebugSessionsTool())
        register(StartDebugSessionTool())
        register(StopDebugSessionTool())
        register(GetDebugSessionStatusTool())

        // Breakpoint Tools (P0)
        register(ListBreakpointsTool())
        register(SetBreakpointTool())
        register(RemoveBreakpointTool())

        // Breakpoint Tools (P2)
        register(SetExceptionBreakpointTool())

        // Execution Control Tools (P0)
        register(ResumeTool())
        register(PauseTool())
        register(StepOverTool())
        register(StepIntoTool())

        // Execution Control Tools (P1)
        register(StepOutTool())
        register(RunToLineTool())

        // Stack Frame Tools (P0)
        register(GetStackTraceTool())

        // Stack Frame Tools (P1)
        register(SelectStackFrameTool())
        register(ListThreadsTool())

        // Variable Tools (P0)
        register(GetVariablesTool())

        // Variable Tools (P1)
        register(ExpandVariableTool())

        // Variable Tools (P2)
        register(SetVariableTool())

        // Watch Tools (P2)
        register(AddWatchTool())
        register(RemoveWatchTool())

        // Navigation Tools (P1)
        register(GetSourceContextTool())

        // Evaluation Tools
        register(EvaluateTool())
    }
}
