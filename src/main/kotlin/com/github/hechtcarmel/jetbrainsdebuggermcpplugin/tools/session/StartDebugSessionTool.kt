package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SessionInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util.ProcessLogManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Starts a new debug session from a run configuration.
 */
class StartDebugSessionTool : AbstractMcpTool() {

    override val name = "start_debug_session"

    override val description = """
        Starts a new debug session for a specified run configuration and returns the session ID.
        Use this to begin debugging. Call list_run_configurations first to discover available configurations.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Start Debug Session")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("configuration_name") {
                put("type", "string")
                put("description", "Name of the run configuration to debug")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("configuration_name"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val configName = arguments["configuration_name"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: configuration_name")

        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == configName }
            ?: return createErrorResult("Run configuration not found: $configName")

        val executor = DefaultDebugExecutor.getDebugExecutorInstance()

        return try {
            val sessionCountBefore = getDebuggerManager(project).debugSessions.size

            withContext(Dispatchers.Main) {
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }

            // Wait for the session to be created (with timeout)
            val newSession = withTimeoutOrNull(5000L) {
                while (true) {
                    delay(100)
                    val sessions = getDebuggerManager(project).debugSessions
                    if (sessions.size > sessionCountBefore) {
                        val newest = sessions.lastOrNull()
                        if (newest != null) {
                            return@withTimeoutOrNull newest
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            if (newSession != null) {
                val processHandler = newSession.debugProcess.processHandler
                if (!ProcessLogManager.hasListener(processHandler.hashCode())) {
                    println("[StartDebugSessionTool] Attaching listener to process: ${processHandler.hashCode()}")
                    ProcessLogManager.attachListener(processHandler)
                }

                createJsonResult(StartDebugSessionResult(
                    status = "started",
                    message = "Debug session started for: $configName",
                    session_info = SessionInfo(
                        id = processHandler.hashCode().toString(),
                        name = newSession.sessionName,
                        type = "debug",
                        state = if (newSession.isPaused) "paused" else "running",
                        isCurrent = newSession == getCurrentSession(project),
                        runConfigurationName = configName
                    )
                ))
            } else {
                createJsonResult(StartDebugSessionResult(
                    status = "starting",
                    message = "Debug session starting for: $configName (may take a moment to initialize)",
                    session_info = null
                ))
            }
        } catch (e: Exception) {
            createErrorResult("Failed to start debug session: ${e.message}")
        }
    }
}

@Serializable
data class StartDebugSessionResult(
    val status: String,
    val message: String,
    val session_info: SessionInfo?
)
