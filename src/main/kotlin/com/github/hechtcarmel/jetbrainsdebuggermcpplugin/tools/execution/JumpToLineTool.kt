package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `jump_to_line` — "Set Next Statement" / "Jump to Cursor": moves the paused execution point to
 * another line of the current function without executing the lines in between.
 *
 * The platform has no language-agnostic API for this (`XDebugProcess` has none on any branch through
 * 2026.3), so support is per debugger. Today that means Python sessions on the pydevd backend,
 * through [PydevdSetNextStatement]. Every other debugger gets an error naming its debug process, so
 * a report of "not supported by X" says exactly which backend a future bridge has to cover.
 *
 * pydevd answers the request first and re-suspends the thread at the new line afterwards, so the
 * tool listens for that pause *before* sending the request and only reports success once the
 * session's position has actually moved — unlike `run_to_line`, whose target may never be reached.
 */
class JumpToLineTool : AbstractMcpTool() {

    override val name = "jump_to_line"

    override val description = """
        Moves the paused execution point to another line WITHOUT running the code in between (also known as Set Next Statement, Jump to Cursor, or Set Execution Point). Unlike run_to_line, skipped lines never execute, and jumping to an earlier line executes it again. The session stays paused at the new line.
        Use to retry a block after fixing a value with set_variable, or to skip a call that crashes or has side effects, without restarting the session.
        Works only inside the current function of the paused thread (its top stack frame, regardless of select_stack_frame), and only where the debugger supports it: currently Python sessions on the pydevd debugger backend. Other debuggers, including Java/Kotlin and Python's debugpy backend, return an error.
        Skipped code leaves variables stale or unassigned, jumping back re-runs side effects, and skipped 'finally' blocks or 'with' exits do not run. Python refuses jumps into a 'for' loop body or an 'except' block.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Jump to Line", destructive = true)

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("file_path", stringProperty("Absolute path to the source file. Must be the file of the current execution point."))
            put("line", integerProperty("Target line number (1-based) in the current function. It becomes the next line to execute.", minimum = 1))
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val filePath = ToolArguments.requireString(arguments, "file_path")
        val line = ToolArguments.requireInt(arguments, "line", min = 1)

        val session = requirePausedSession(project, sessionId, "jump to line")

        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult("File not found: $filePath")

        val executionPoint = session.topFramePosition

        val setNextStatement = PydevdSetNextStatement.find(session.debugProcess)
            ?: return createErrorResult(unsupportedDebuggerMessage(session, executionPoint))

        if (executionPoint == null) {
            return createErrorResult("Cannot jump: the paused session has no current execution point")
        }

        // pydevd receives only the line number and the name of the target's enclosing function — never
        // the file. A target elsewhere would move execution to that line number of the *current* file.
        if (!isSameFile(executionPoint.file, virtualFile)) {
            return createErrorResult(
                "Cannot jump to $filePath:$line: the target must be in the file of the current execution " +
                    "point (${executionPoint.file.path}:${executionPoint.line + 1}). The debugger can only " +
                    "move execution within the current function."
            )
        }

        val target = XDebuggerUtil.getInstance().createPosition(virtualFile, line - 1)
            ?: return createErrorResult("Cannot create position for $filePath:$line")

        val repositioned = CompletableDeferred<Boolean>()
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                repositioned.complete(true)
            }

            override fun sessionStopped() {
                repositioned.complete(false)
            }
        }
        // Registered before the request is sent: the pause that reports the new position can be
        // delivered before the request's own reply has been handed back.
        session.addSessionListener(listener)
        try {
            when (val reply = setNextStatement.request(session.suspendContext, target, REPLY_TIMEOUT_MS)) {
                is PydevdSetNextStatement.Reply.Refused ->
                    return createErrorResult("Cannot jump to $filePath:$line: ${reply.reason}")
                is PydevdSetNextStatement.Reply.Failed ->
                    return createErrorResult("Failed to jump to line: ${reply.reason}")
                PydevdSetNextStatement.Reply.NoReply ->
                    return createErrorResult(
                        "The debugger did not answer the jump request within ${REPLY_TIMEOUT_MS / 1000}s. " +
                            "Execution was not moved; the thread may not be stopped at a line."
                    )
                PydevdSetNextStatement.Reply.Accepted -> Unit
            }

            val paused = withTimeoutOrNull(REPOSITION_TIMEOUT_MS) { repositioned.await() }
            if (paused == false || session.isStopped) {
                return createErrorResult("The debug session ended during the jump")
            }

            return createJsonResult(ExecutionControlResult(
                sessionId = getSessionId(session),
                action = "jump_to_line",
                status = "success",
                message = successMessage(session.topFramePosition, virtualFile, line, observed = paused == true),
                newState = "paused"
            ))
        } finally {
            session.removeSessionListener(listener)
        }
    }

    private fun successMessage(position: XSourcePosition?, targetFile: VirtualFile, targetLine: Int, observed: Boolean): String {
        val target = "${targetFile.path}:$targetLine"
        return when {
            !observed ->
                "The debugger accepted the jump to $target but did not report the new position within " +
                    "${REPOSITION_TIMEOUT_MS / 1000}s. Call get_debug_session_status to confirm where execution is paused."
            position != null && isSameFile(position.file, targetFile) && position.line == targetLine - 1 ->
                "Execution point moved to $target. The skipped lines did not run."
            else ->
                "The debugger accepted the jump to $target, but the session is now paused at " +
                    "${position?.let { "${it.file.path}:${it.line + 1}" } ?: "an unknown position"}."
        }
    }

    private fun unsupportedDebuggerMessage(session: XDebugSession, executionPoint: XSourcePosition?): String {
        val base = "Jump to line is not supported by this debugger (${session.debugProcess.javaClass.name}). " +
            "It is currently available in Python debug sessions that use the pydevd debugger backend."
        val inPythonCode = executionPoint?.file?.extension?.lowercase() in PYTHON_EXTENSIONS
        return if (inPythonCode) {
            "$base This Python session uses another backend (such as debugpy): switch the Python " +
                "\"Debugger mode\" setting to pydevd and restart the debug session to use jump_to_line."
        } else {
            base
        }
    }

    private fun isSameFile(a: VirtualFile, b: VirtualFile): Boolean =
        a == b || (a.canonicalFile ?: a) == (b.canonicalFile ?: b)

    companion object {
        /** pydevd answers within milliseconds; the margin covers a busy debugger connection. */
        private const val REPLY_TIMEOUT_MS = 10_000L

        /** Upper bound for the re-suspension that follows an accepted jump. */
        private const val REPOSITION_TIMEOUT_MS = 10_000L

        private val PYTHON_EXTENSIONS = setOf("py", "pyw")
    }
}
