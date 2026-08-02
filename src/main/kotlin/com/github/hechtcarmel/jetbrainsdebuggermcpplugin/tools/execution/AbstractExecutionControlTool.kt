package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Base for the five session-only execution-control tools (`resume_execution`, `pause_execution`,
 * `step_over`, `step_into`, `step_out`), which differ only in wording, pause-state precondition,
 * and the one [XDebugSession] method they call on the EDT.
 *
 * Subclasses supply every client-visible string as a literal (name, description, result fields,
 * pause-state error, failure prefix) — the strings are the pinned contract, and keeping them
 * verbatim in each tool file is what lets the source-level pins (`ThreadingConventionsTest`)
 * and a reader grep for them.
 *
 * `run_to_line` stays separate: it takes `file_path`/`line` arguments and resolves a position.
 */
abstract class AbstractExecutionControlTool : AbstractMcpTool() {

    /** The shared session-only input schema — byte-identical across all five tools. */
    final override val inputSchema: JsonObject = buildJsonObject {
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

    /** Wire value of the result's `action` field, e.g. `"step_over"`. */
    protected abstract val action: String

    /** Wire value of the result's `message` field on success, e.g. `"Stepped over"`. */
    protected abstract val successMessage: String

    /** Wire value of the result's `newState` field on success: `"running"` or `"paused"`. */
    protected abstract val newState: String

    /** Literal failure-message prefix, e.g. `"Failed to step over"` — completed with `: <cause>`. */
    protected abstract val failurePrefix: String

    /**
     * Returns the pinned error message when the session's pause state forbids this action,
     * or null to proceed.
     */
    protected abstract fun checkPauseState(session: XDebugSession): String?

    /** The one debugger call this tool exists for. Invoked on the EDT. */
    protected abstract fun performAction(session: XDebugSession)

    final override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val session = requireSession(project, sessionId)

        checkPauseState(session)?.let { return createErrorResult(it) }

        return try {
            // Execution-control methods must be called from the EDT
            onEdt {
                performAction(session)
            }
            createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = action,
                status = "success",
                message = successMessage,
                newState = newState
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("$failurePrefix: ${e.message}")
        }
    }
}
