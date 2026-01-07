package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.ProcessLogManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class RunConfigurationTool : AbstractMcpTool() {
    override val name = "execute_run_configuration"
    override val description = """
        Executes a run configuration in either 'run' or 'debug' mode.
        Use when you need to run or debug a specific configuration. For debugging with full session tracking, prefer start_debug_session instead.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Execute Configuration")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("name") {
                put("type", "string")
                put("description", "Name of the run configuration to execute")
            }
            putJsonObject("mode") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("debug"))
                    add(JsonPrimitive("run"))
                }
                put("description", "Execution mode: 'debug' (default) or 'run'")
                put("default", "debug")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("name"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val configName = arguments["name"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: name")
        val mode = arguments["mode"]?.jsonPrimitive?.content ?: "debug"

        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == configName }
            ?: return createErrorResult("Run configuration not found: $configName")

        val executor = when (mode) {
            "run" -> DefaultRunExecutor.getRunExecutorInstance()
            "debug" -> DefaultDebugExecutor.getDebugExecutorInstance()
            else -> return createErrorResult("Invalid mode: $mode. Use 'run' or 'debug'")
        }

        return try {
            // Execute the configuration on the EDT (Main UI Thread)
            withContext(Dispatchers.Main) {
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }

            // Wait briefly for the process to start and become visible in ExecutionManager
            // This delay might need adjustment based on startup time of the application/config
            delay(500) // Increased delay to ensure process is ready

            // Attach listener to the newly started process
            val processHandler = attachListenerToNewProcess(project)
            val sessionId = processHandler?.hashCode()?.toString() ?: "unknown"

            createJsonResult(RunConfigurationResult(
                configurationName = configName,
                mode = mode,
                status = "started",
                message = "Started $configName in $mode mode",
                sessionId = sessionId // Include the session ID (hashCode) for get_run_log
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to start configuration: ${e.message}")
        }
    }

    /**
     * Finds the newly started process and attaches a log listener to it.
     * Assumes that after calling executeConfiguration, a new process should appear.
     * @param project The current project.
     * @return The ProcessHandler that was found and listened to, or null if not found.
     */
    private suspend fun attachListenerToNewProcess(project: Project): ProcessHandler? {
        val executionManager = com.intellij.execution.ExecutionManager.getInstance(project)
        // Polling mechanism to find a new process that doesn't have a listener yet
        for (i in 0..10) { // Try up to 10 times with a small delay
            val runningProcesses: Array<ProcessHandler> = executionManager.getRunningProcesses()
            for (processHandler in runningProcesses) {
                val processHashCode = processHandler.hashCode()
                if (!ProcessLogManager.hasListener(processHashCode)) {
                    println("[RunConfigurationTool] Found new process to attach listener: $processHashCode")
                    ProcessLogManager.attachListener(processHandler)
                    return processHandler // Return the handler that was just listened to
                }
            }
            delay(2000) // Wait a bit before checking again
        }
        println("[RunConfigurationTool] Could not find a new process to attach listener to after several attempts.")
        return null
    }
}