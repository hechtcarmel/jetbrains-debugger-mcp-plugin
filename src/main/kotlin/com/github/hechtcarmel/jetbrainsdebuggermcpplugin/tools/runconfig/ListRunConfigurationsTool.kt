package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationListResult
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lists all available run configurations in the project.
 *
 * Use this tool to discover what configurations are available before
 * starting a debug session.
 */
class ListRunConfigurationsTool : AbstractMcpTool() {

    override val name = "list_run_configurations"

    override val description = """
        Lists all available run/debug configurations in the project.
        Use this first to discover which configurations can be debugged before calling start_debug_session.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("List Run Configurations")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val runManager = RunManager.getInstance(project)
        val allSettings = runManager.allSettings
        val selectedConfig = runManager.selectedConfiguration

        val configurations = allSettings.map { settings ->
            val configuration = settings.configuration
            val configurationType = settings.type

            RunConfigurationInfo(
                name = settings.name,
                type = configurationType.displayName,
                typeId = configurationType.id,
                isTemporary = settings.isTemporary,
                canRun = canExecute(project, settings, DefaultRunExecutor.getRunExecutorInstance()),
                canDebug = canExecute(project, settings, DefaultDebugExecutor.getDebugExecutorInstance()),
                folder = settings.folderName,
                description = configuration.toString().takeIf { it != settings.name }
            )
        }

        return createJsonResult(RunConfigurationListResult(
            configurations = configurations,
            activeConfiguration = selectedConfig?.name
        ))
    }

    private fun canExecute(
        project: Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        executor: com.intellij.execution.Executor
    ): Boolean {
        return try {
            val runner = com.intellij.execution.runners.ProgramRunner.getRunner(
                executor.id,
                settings.configuration
            )
            runner != null
        } catch (e: Exception) {
            false
        }
    }
}
