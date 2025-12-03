package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationResult
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
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
 * Runs or debugs a run configuration by name.
 *
 * Use this tool to start a debug session from a specific run configuration.
 */
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
            val environmentBuilder = ExecutionEnvironmentBuilder
                .createOrNull(executor, settings)
                ?: return createErrorResult("Cannot create execution environment for: $configName")

            val environment = environmentBuilder.build()

            withContext(Dispatchers.Main) {
                ExecutionManager.getInstance(project).restartRunProfile(environment)
            }

            createJsonResult(RunConfigurationResult(
                configurationName = configName,
                mode = mode,
                status = "started",
                message = "Started $configName in $mode mode"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to start configuration: ${e.message}")
        }
    }
}
