package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
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
 * Runs or debugs a run configuration by name.
 *
 * Use this tool to start a debug session from a specific run configuration.
 */
class RunConfigurationTool : AbstractMcpTool() {

    override val name = "execute_run_configuration"

    override val description = """
        Executes a run configuration in either 'run' or 'debug' mode.
        Use when you need to run or debug a specific configuration. For debugging with full session tracking, prefer start_debug_session instead.
        Run mode returns a run: sessionId before asynchronous startup completes. status=starting is not confirmation of success; inspect list_run_sessions/get_run_console_output for failed_start, exit code and retained output. Use stop_run_session for that exact Run ID. Debug tools do not accept Run IDs.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Execute Configuration")

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

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val configName = ToolArguments.requireString(arguments, "name")
        // The historical "Invalid mode: ..." check below stays authoritative for unknown values.
        val mode = ToolArguments.optionalString(arguments, "mode") ?: "debug"

        val runManager = RunManager.getInstance(project)
        val matches = runManager.allSettings.filter { it.name == configName }
        if (mode == "run" && matches.size > 1) {
            return createErrorResult("Several Run configurations are named '$configName'; rename them uniquely before executing")
        }
        val settings = matches.firstOrNull()
            ?: return createErrorResult("Run configuration not found: $configName")

        val executor = when (mode) {
            "run" -> DefaultRunExecutor.getRunExecutorInstance()
            "debug" -> DefaultDebugExecutor.getDebugExecutorInstance()
            else -> return createErrorResult("Invalid mode: $mode. Use 'run' or 'debug'")
        }

        return try {
            if (mode == "run") {
                val result = onEdt {
                    val service = RunSessionService.getInstance(project)
                    val id = service.reserve(configName)
                    try {
                        val environment = ExecutionEnvironmentBuilder.create(executor, settings)
                            .contentToReuse(null).activeTarget().build(object : ProgramRunner.Callback {
                                override fun processStarted(descriptor: RunContentDescriptor) {
                                    service.event { bindDescriptor(id, descriptor) }
                                }

                                override fun processNotStarted(error: Throwable?) {
                                    service.event { failed(id, error?.message) }
                                }

                                @Suppress("DEPRECATION")
                                override fun processNotStarted() {
                                    service.event { failed(id, null) }
                                }
                            })
                        environment.assignNewExecutionId()
                        environment.putUserData(RunSessionService.SESSION_KEY, id)
                        service.scheduled(environment)
                        // Keep the reserved environment identity through the async launch.
                        if (ExecutionManager.getInstance(project).isStarting(environment)) {
                            service.failed(id, "This configuration already has a startup in progress; no new launch was performed")
                        } else {
                            ProgramRunnerUtil.executeConfiguration(environment, false, false)
                        }
                    } catch (e: CancellationException) {
                        service.failed(id, "Execution was cancelled before startup completed")
                        throw e
                    } catch (e: Exception) {
                        service.failed(id, e.message)
                    }
                    val session = service.session(id)
                    RunConfigurationResult(configName, mode, session.state,
                        session.failureMessage ?: "Execution requested; inspect this Run session for startup and exit status", id)
                }
                return createJsonResult(result).copy(isError = result.status == "failed_start")
            }
            withContext(Dispatchers.EDT) {
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }

            createJsonResult(RunConfigurationResult(
                configurationName = configName,
                mode = mode,
                status = "started",
                message = "Started $configName in $mode mode"
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to start configuration: ${e.message}")
        }
    }
}
