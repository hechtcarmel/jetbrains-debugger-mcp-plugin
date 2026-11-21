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
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.channels.Channel
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
 * 2026.3), so support is per debugger, through the reflective [PydevdSetNextStatement] entry point.
 * Backends without that method get an error naming the debug process. A backend that moves without
 * the pydevd callback/pause sequence can instead be confirmed at the exact changed target.
 *
 * pydevd answers the request first and re-suspends the thread at the new line afterwards, so the
 * tool listens for that pause *before* sending the request and only reports success once the jumped
 * thread has been seen at its new position — unlike `run_to_line`, whose target may never be reached.
 * An accepted jump whose new position never arrives is an error: the tool will not claim a state it
 * has not observed.
 */
class JumpToLineTool : AbstractMcpTool() {

    override val name = "jump_to_line"

    override val description = """
        Moves the paused execution point to another line WITHOUT running the code in between (also known as Set Next Statement, Jump to Cursor, or Set Execution Point). Unlike run_to_line, skipped lines never execute, and jumping to an earlier line executes it again. The session stays paused at the new line.
        Use to retry a block after fixing a value with set_variable, or to skip a call that crashes or has side effects, without restarting the session.
        Works only inside the current function of the thread that paused (its top stack frame, regardless of select_stack_frame), and only where the debugger supports it: currently Python sessions on the pydevd debugger backend. Other debuggers, including Java/Kotlin and Python's debugpy backend, return an error.
        Skipped code leaves variables stale or unassigned (Python 3.12+ sets unassigned locals to None), jumping back re-runs side effects, and skipped 'finally' blocks or 'with' exits do not run. Python refuses jumps into a 'for' loop body or an 'except' block, and a thread stopped right after step_out or a step past a 'return' must step once more before it can jump. A target line without code (blank or comment) lands on the next line that has code.
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

        // pydevd moves the thread that stopped — the suspend context's active stack — whatever frame or
        // thread select_stack_frame has put in front since. Every check below is made against that thread.
        val before = capturePause(session)
        val suspendContext = before.context
        val jumpedStack = before.stack
        val executionPoint = before.position

        val setNextStatement = PydevdSetNextStatement.find(before.snapshot.process)
            ?: return createErrorResult(readAction { unsupportedDebuggerMessage(session, executionPoint) })

        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult(
                "File not found: $filePath. " +
                    "For files inside JAR archives, use the '!/' separator " +
                    "(e.g. /path/to/lib-sources.jar!/com/example/Foo.kt)."
            )

        if (suspendContext == null || jumpedStack == null || executionPoint == null) {
            return createErrorResult("Cannot jump: the paused session has no current execution point")
        }

        // pydevd receives only the line number and the name of the target's enclosing function — never
        // the file. A target elsewhere would move execution to that line number of the *current* file.
        if (!readAction { isSameFile(executionPoint.file, virtualFile) }) {
            val currentPosition = readAction { "${executionPoint.file.path}:${executionPoint.line + 1}" }
            return createErrorResult(
                "Cannot jump to $filePath:$line: the target must be in the file of the current execution " +
                    "point ($currentPosition). The debugger can only " +
                    "move execution within the current function."
            )
        }

        val target = XDebuggerUtil.getInstance().createPosition(virtualFile, line - 1)
            ?: return createErrorResult("Cannot create position for $filePath:$line")

        val targetLocation = readAction { location(target) }
        var confirmedAtTarget = false
        val confirmsTarget: suspend () -> Boolean = {
            JumpTargetConfirmation.confirmsMove(before.snapshot, capturePause(session).snapshot, targetLocation)
                .also { if (it) confirmedAtTarget = true }
        }

        // Events wake the observer; state is also sampled because debugpy can update a frame
        // without replacing its suspend context or delivering a sessionPaused/callback pair.
        val sessionEvents = Channel<Boolean>(Channel.CONFLATED)
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                sessionEvents.trySend(true)
            }

            override fun stackFrameChanged() {
                sessionEvents.trySend(true)
            }

            override fun sessionStopped() {
                sessionEvents.trySend(false)
            }
        }
        // Registered before the request is sent: pydevd re-suspends the thread within milliseconds of
        // answering, so the pause can land before this coroutine has even read the answer.
        session.addSessionListener(listener)
        try {
            when (val reply = setNextStatement.request(suspendContext, target, REPLY_TIMEOUT_MS, confirmsTarget)) {
                is PydevdSetNextStatement.Reply.Refused ->
                    return createErrorResult("Cannot jump to $filePath:$line: ${reply.reason}")
                is PydevdSetNextStatement.Reply.Failed ->
                    return createErrorResult("Failed to jump to line: ${reply.reason}")
                PydevdSetNextStatement.Reply.NoReply ->
                    return createErrorResult(
                        "The debugger did not answer the jump request within ${REPLY_TIMEOUT_MS / 1000}s; it may " +
                            "still apply it. Check where execution is paused with get_debug_session_status " +
                            "before retrying."
                    )
                PydevdSetNextStatement.Reply.Accepted -> Unit
            }

            val outcome = if (confirmedAtTarget) Reposition.Moved(target)
                else awaitReposition(session, before, target, sessionEvents, confirmsTarget)
            return when (outcome) {
                Reposition.Stopped -> createErrorResult("The debug session ended during the jump")
                null -> createErrorResult(
                    "The debugger accepted the jump to $filePath:$line but did not report the new position " +
                        "within ${REPOSITION_TIMEOUT_MS / 1000}s. Call get_debug_session_status to see where " +
                        "execution is before continuing."
                )
                is Reposition.Moved -> createJsonResult(ExecutionControlResult(
                    sessionId = getSessionId(session),
                    action = "jump_to_line",
                    status = "success",
                    message = readAction { successMessage(outcome.position, virtualFile, line) },
                    newState = "paused"
                ))
            }
        } finally {
            session.removeSessionListener(listener)
            sessionEvents.close()
        }
    }

    private sealed interface Reposition {
        data object Stopped : Reposition
        data class Moved(val position: XSourcePosition?) : Reposition
    }

    private data class PauseState(
        val context: XSuspendContext?,
        val stack: XExecutionStack?,
        val position: XSourcePosition?,
        val snapshot: JumpTargetConfirmation.Snapshot,
    )

    private suspend fun capturePause(session: XDebugSession): PauseState = readAction {
        val stopped = session.isStopped
        val paused = !stopped && session.isPaused
        val context = if (paused) session.suspendContext else null
        val stack = context?.activeExecutionStack
        val position = stack?.topFrame?.sourcePosition
        PauseState(context, stack, position, JumpTargetConfirmation.Snapshot(
            session = session,
            process = session.debugProcess,
            thread = stack,
            location = position?.let { location(it) },
            paused = paused,
            stopped = stopped,
        ))
    }

    private fun location(position: XSourcePosition): JumpTargetConfirmation.Location =
        JumpTargetConfirmation.Location((position.file.canonicalFile ?: position.file).url, position.line)

    /**
     * Exact-target state confirmation also works when a backend reuses its suspend context or
     * only refreshes a stack frame. Preserve callback-acknowledged pydevd remapping (e.g. a blank
     * target line) only after a fresh pause, in the same execution stack, at a changed location.
     */
    private suspend fun awaitReposition(
        session: XDebugSession,
        before: PauseState,
        target: XSourcePosition,
        sessionEvents: Channel<Boolean>,
        confirmsTarget: suspend () -> Boolean,
    ): Reposition? = withTimeoutOrNull(REPOSITION_TIMEOUT_MS) {
        while (true) {
            if (confirmsTarget()) return@withTimeoutOrNull Reposition.Moved(target)
            if (session.isStopped) return@withTimeoutOrNull Reposition.Stopped
            val event = withTimeoutOrNull(JumpTargetConfirmation.POLL_INTERVAL_MS) { sessionEvents.receive() }
                ?: continue
            if (!event) return@withTimeoutOrNull Reposition.Stopped
            val current = capturePause(session)
            if (current.snapshot.paused && current.context !== before.context &&
                current.stack == before.stack && current.snapshot.process === before.snapshot.process &&
                current.snapshot.location != null && current.snapshot.location != before.snapshot.location &&
                current.snapshot.location.file == before.snapshot.location?.file
            ) {
                return@withTimeoutOrNull Reposition.Moved(current.position)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun successMessage(position: XSourcePosition?, targetFile: VirtualFile, targetLine: Int): String {
        val target = "${targetFile.path}:$targetLine"
        return when {
            position == null ->
                "The debugger accepted the jump to $target but reports no current position. " +
                    "Call get_debug_session_status to confirm where execution is paused."
            isSameFile(position.file, targetFile) && position.line == targetLine - 1 ->
                "Execution point moved to $target. The skipped lines did not run."
            // CPython moves a jump onto a line without code (blank, comment) to the next line that has some.
            isSameFile(position.file, targetFile) ->
                "Execution point moved to ${position.file.path}:${position.line + 1}: line $targetLine has no code, " +
                    "so the debugger used the next line that does. The skipped lines did not run."
            else ->
                "The debugger accepted the jump to $target, but the session is now paused at " +
                    "${position.file.path}:${position.line + 1}."
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
