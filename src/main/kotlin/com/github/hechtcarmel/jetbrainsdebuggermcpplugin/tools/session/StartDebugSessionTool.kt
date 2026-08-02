package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    override val annotations = ToolAnnotationPresets.mutable("Start Debug Session")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("configuration_name", stringProperty("Name of the run configuration to debug"))
        }
        putJsonArray("required") {
            add(JsonPrimitive("configuration_name"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val configName = ToolArguments.requireString(arguments, "configuration_name")

        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == configName }
            ?: return createErrorResult("Run configuration not found: $configName")

        val executor = DefaultDebugExecutor.getDebugExecutorInstance()

        return try {
            val sessionCountBefore = getDebuggerManager(project).debugSessions.size

            withContext(Dispatchers.EDT) {
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }

            // Wait for the session to be created (with timeout)
            val newSession = withTimeoutOrNull(30000L) {
                while (true) {
                    delay(1000)
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
                createJsonResult(StartDebugSessionResult(
                    status = "started",
                    message = "Debug session started for: $configName",
                    session = DebugSessionInfo(
                        id = getSessionId(newSession),
                        name = newSession.sessionName,
                        state = if (newSession.isPaused) "paused" else "running",
                        isCurrent = newSession == getCurrentSession(project),
                        runConfigurationName = configName
                    )
                ))
            } else {
                createJsonResult(StartDebugSessionResult(
                    status = "starting",
                    message = "Debug session starting for: $configName (may take a moment to initialize)",
                    session = null
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to start debug session: ${e.message}")
        }
    }
}

@Serializable
data class StartDebugSessionResult(
    val status: String,
    val message: String,
    val session: DebugSessionInfo?
)
