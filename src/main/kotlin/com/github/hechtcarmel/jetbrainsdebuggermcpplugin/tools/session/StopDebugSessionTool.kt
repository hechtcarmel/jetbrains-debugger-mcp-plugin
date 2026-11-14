package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StopSessionResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ProcessTermination
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Stops an active debug session.
 */
class StopDebugSessionTool : AbstractMcpTool() {

    override val name = "stop_debug_session"

    override val description = """
        Terminates a debug session, stopping the debugged process.
        Use to end a debugging session. This is a destructive operation that cannot be undone.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Stop Debug Session", destructive = true)

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")

        return try {
            val target = onEdt {
                val session = requireSession(project, sessionId)
                val handler = session.debugProcess.processHandler
                DebugStopTarget(getSessionId(session), session.sessionName, handler, handler.detachIsDefault())
            }
            // XDebugSessionImpl.stop uses this handler's detach policy. Capture on the
            // EDT, but do not perform Gradle/external-system cancellation while on it.
            ProcessTermination.request(target.handler, target.detach)
            createJsonResult(StopSessionResult(
                sessionId = target.id,
                status = "stopped",
                message = "Debug session '${target.name}' stopped"
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolExecutionError) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to stop session: ${e.message}")
        }
    }

    private data class DebugStopTarget(
        val id: String,
        val name: String,
        val handler: com.intellij.execution.process.ProcessHandler,
        val detach: Boolean
    )
}
