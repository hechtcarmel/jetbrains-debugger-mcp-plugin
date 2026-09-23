package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.PydevdSetNextStatement.Reply
import com.intellij.openapi.util.Pair
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.runBlocking

/**
 * The reflective half of `jump_to_line`, driven with stand-ins shaped like PyCharm's
 * `PyDebugProcess.startSetNextStatement(XSuspendContext?, XSourcePosition, PyDebugCallback<Pair<Boolean, String>>)`.
 *
 * The stand-ins mirror what pydevd's IDE side really does with the callback: `SetNextStatementCommand`
 * calls `ok(Pair(accepted, "Error" | "Error: <reason>"))` on the requesting thread, or `error(...)` when
 * the reply cannot be parsed, and `startSetNextStatement` returns without calling back at all when the
 * thread is not suspended. The end-to-end path against a real pydevd is covered by
 * `livedebug/LivePythonDebugSessionTest`.
 */
class PydevdSetNextStatementTest : BasePlatformTestCase() {

    /** Same shape as `com.jetbrains.python.debugger.pydev.PyDebugCallback`. */
    interface StandInCallback<T> {
        fun ok(value: T)
        fun error(exception: Exception)
    }

    open class ReplyingProcess(private val answer: (StandInCallback<Pair<Boolean, String>>) -> Unit) {
        @Volatile
        var requestedLine: Int? = null

        @Volatile
        var requestedContext: XSuspendContext? = null

        fun startSetNextStatement(
            context: XSuspendContext?,
            sourcePosition: XSourcePosition,
            callback: StandInCallback<Pair<Boolean, String>>,
        ) {
            requestedContext = context
            requestedLine = sourcePosition.line
            answer(callback)
        }
    }

    class SubclassedProcess : ReplyingProcess({ it.ok(Pair(true, "Error")) })

    class JvmLikeProcess {
        fun startStepOver(context: XSuspendContext?) = Unit
    }

    class WrongShapeProcess {
        fun startSetNextStatement(line: Int, callback: StandInCallback<Pair<Boolean, String>>) = Unit
    }

    private val target: XSourcePosition by lazy {
        val file = LightVirtualFile("target.py", "total = 0\nfor i in range(3):\n    total += i\nprint(total)\n")
        requireNotNull(XDebuggerUtil.getInstance().createPosition(file, 3))
    }

    private fun request(process: Any, timeoutMs: Long = 5_000): Reply {
        val bridge = requireNotNull(PydevdSetNextStatement.find(process)) {
            "Expected a bridge for ${process.javaClass.simpleName}"
        }
        return runBlocking { bridge.request(null, target, timeoutMs) }
    }

    // ── Discovery ───────────────────────────────────────────────────────────────────────

    fun `test a process without startSetNextStatement has no bridge`() {
        assertNull(PydevdSetNextStatement.find(JvmLikeProcess()))
        assertNull(PydevdSetNextStatement.find(Any()))
    }

    fun `test a startSetNextStatement with a different parameter shape is not mistaken for pydevd`() {
        assertNull(PydevdSetNextStatement.find(WrongShapeProcess()))
    }

    fun `test the method is found on a subclass as with the pydevd remote debug process`() {
        assertEquals(Reply.Accepted, request(SubclassedProcess()))
    }

    // ── The request ─────────────────────────────────────────────────────────────────────

    fun `test the target position is passed through with its 0-based line`() {
        val process = ReplyingProcess { it.ok(Pair(true, "Error")) }
        request(process)
        assertEquals(3, process.requestedLine)
    }

    // ── Reading the reply ───────────────────────────────────────────────────────────────

    fun `test an accepted reply is Accepted even though pydevd pads it with Error`() {
        assertEquals(Reply.Accepted, request(ReplyingProcess { it.ok(Pair(true, "Error")) }))
    }

    fun `test a refusal carries the pydevd reason without the Error prefix`() {
        assertEquals(
            Reply.Refused("can't jump into the body of a for loop"),
            request(ReplyingProcess { it.ok(Pair(false, "Error: can't jump into the body of a for loop")) })
        )
        assertEquals(
            Reply.Refused("jump is available only within the bottom frame"),
            request(ReplyingProcess { it.ok(Pair(false, "Error: jump is available only within the bottom frame")) })
        )
        assertEquals(
            Reply.Refused("can't jump into an 'except' block as there's no exception"),
            request(ReplyingProcess { it.ok(Pair(false, "Error: can't jump into an 'except' block as there's no exception")) })
        )
    }

    fun `test a refusal without a reason explains that the thread is not at a line start`() {
        // pydevd answers a bare False when the thread did not stop on a 'line' event, e.g. after step_out.
        assertEquals(
            Reply.Refused(PydevdSetNextStatement.NOT_AT_LINE_START_REASON),
            request(ReplyingProcess { it.ok(Pair(false, "Error")) })
        )
    }

    fun `test an unparseable reply is a failure`() {
        assertEquals(
            Reply.Failed("Unable to parse value: garbage"),
            request(ReplyingProcess { it.error(IllegalStateException("Unable to parse value: garbage")) })
        )
        assertEquals(
            Reply.Failed("unexpected reply from the debugger: null"),
            PydevdSetNextStatement.parseReply(null)
        )
    }

    fun `test an exception from the debugger is a failure and does not escape`() {
        assertEquals(
            Reply.Failed("Debugger is not connected"),
            request(ReplyingProcess { throw IllegalStateException("Debugger is not connected") })
        )
    }

    fun `test a debugger that never calls back times out as NoReply`() {
        val started = System.currentTimeMillis()
        assertEquals(Reply.NoReply, request(ReplyingProcess { }, timeoutMs = 300))
        assertTrue(
            "NoReply must come from the timeout, not from waiting on the debugger",
            System.currentTimeMillis() - started < 5_000
        )
    }

    fun `test a reply from another thread is still delivered`() {
        val process = ReplyingProcess { callback ->
            Thread { callback.ok(Pair(true, "Error")) }.start()
        }
        assertEquals(Reply.Accepted, request(process))
    }
}
