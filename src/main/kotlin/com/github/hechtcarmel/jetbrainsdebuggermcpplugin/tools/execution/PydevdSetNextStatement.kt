package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reflective bridge to PyCharm's pydevd "Jump to Cursor":
 * `com.jetbrains.python.debugger.PyDebugProcess.startSetNextStatement(XSuspendContext?, XSourcePosition,
 * PyDebugCallback<Pair<Boolean, String>>)`.
 *
 * That method is public, carries no `@ApiStatus` annotation, and has the same signature on every
 * IntelliJ branch from 2025.2 (252) through 2026.3 (263) — it is what PyCharm's own `SetNextStatement`
 * action and its debugger test harness call. It is reached reflectively, matched by name and
 * parameter shape on whatever the session's debug process is, so this plugin needs no compile-time
 * dependency on the Python plugin and keeps loading in IDEs without it. Debug processes that do not
 * have the method (the JVM debugger, the debugpy/DAP backend, native debuggers) get no bridge, and
 * [find] returns null for them.
 *
 * What pydevd does with the request (`python/helpers/pydev/pydevd.py`, `set_next_statement`): it
 * checks that the target lies in the function of the thread's *top* frame — the IDE sends only the
 * line and that function's name, never the file — and assigns `frame.f_lineno`, answering with
 * CPython's refusal text when the interpreter rejects the jump. On success it re-suspends the
 * thread at the new line, which reaches the IDE as a fresh `sessionPaused`.
 */
internal class PydevdSetNextStatement private constructor(
    private val process: Any,
    private val method: Method,
) {

    sealed interface Reply {
        /** pydevd moved the frame; a fresh pause at the new line follows. */
        data object Accepted : Reply

        /** pydevd refused the jump. [reason] is its explanation, e.g. CPython's jump-rule message. */
        data class Refused(val reason: String) : Reply

        /** The request could not be made, or the reply could not be read. */
        data class Failed(val reason: String) : Reply

        /** No answer within the timeout. */
        data object NoReply : Reply
    }

    /**
     * Asks pydevd to make [target] the next line to execute in the thread of [context].
     *
     * `startSetNextStatement` blocks on the debugger socket until pydevd answers (its own limit is
     * 60 s) and resolves the target's enclosing function under a read action, so it runs on a pooled
     * thread — never on the EDT, and never on this coroutine, which gives up after [timeoutMs].
     */
    suspend fun request(context: XSuspendContext?, target: XSourcePosition, timeoutMs: Long): Reply {
        val reply = CompletableDeferred<Reply>()
        val callbackType = method.parameterTypes[CALLBACK_PARAMETER]
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, invoked, args ->
            when (invoked.name) {
                "ok" -> {
                    reply.complete(parseReply(args?.firstOrNull()))
                    null
                }
                "error" -> {
                    reply.complete(Reply.Failed(describe(args?.firstOrNull() as? Throwable)))
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "jump_to_line reply callback"
                else -> null
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            try {
                method.invoke(process, context, target, callback)
            } catch (e: InvocationTargetException) {
                reply.complete(Reply.Failed(describe(e.targetException)))
            } catch (e: ReflectiveOperationException) {
                reply.complete(Reply.Failed(describe(e)))
            } catch (e: RuntimeException) {
                // Method.invoke's own IllegalArgumentException and the like. No coroutine runs on this
                // pooled thread, so there is no cancellation to propagate — only a reply to complete.
                reply.complete(Reply.Failed(describe(e)))
            }
        })

        return withTimeoutOrNull(timeoutMs) { reply.await() } ?: Reply.NoReply
    }

    companion object {
        const val METHOD_NAME = "startSetNextStatement"
        private const val CALLBACK_PARAMETER = 2

        /**
         * The bridge for [debugProcess], or null when it has no `startSetNextStatement(XSuspendContext,
         * XSourcePosition, <callback interface>)` — i.e. when it is not a pydevd debug process.
         */
        fun find(debugProcess: Any): PydevdSetNextStatement? {
            val method = debugProcess.javaClass.methods.firstOrNull { candidate ->
                val parameters = candidate.parameterTypes
                candidate.name == METHOD_NAME &&
                    parameters.size == 3 &&
                    parameters[0].isAssignableFrom(XSuspendContext::class.java) &&
                    parameters[1].isAssignableFrom(XSourcePosition::class.java) &&
                    parameters[CALLBACK_PARAMETER].isInterface
            } ?: return null
            return PydevdSetNextStatement(debugProcess, method)
        }

        /**
         * Why pydevd refuses without a message: it only moves a thread that stopped on a `line` event,
         * and a thread paused right after `step_out`, after stepping past a `return`, or on a `def`
         * line has not.
         */
        const val NOT_AT_LINE_START_REASON =
            "the thread is not stopped at the start of a line (as after step_out or a step past a " +
                "return); step_over once, then retry"

        /**
         * Reads pydevd's reply: a `com.intellij.openapi.util.Pair<Boolean, String>` whose second half
         * is `"Error"` or `"Error: <reason>"` — also on success, so it only means something when the
         * first half is false. The reasons are CPython's jump rules ("can't jump into the body of a
         * for loop", "line 67 comes after the current code block") or pydevd's own ("jump is
         * available only within the bottom frame").
         */
        internal fun parseReply(value: Any?): Reply {
            val pair = value as? com.intellij.openapi.util.Pair<*, *>
                ?: return Reply.Failed("unexpected reply from the debugger: $value")
            if (pair.first == true) return Reply.Accepted
            val reason = (pair.second as? String).orEmpty()
                .trim()
                .removePrefix("Error")
                .removePrefix(":")
                .trim()
            return Reply.Refused(reason.ifEmpty { NOT_AT_LINE_START_REASON })
        }

        private fun describe(throwable: Throwable?): String =
            throwable?.message?.takeIf { it.isNotBlank() }
                ?: throwable?.javaClass?.simpleName
                ?: "unknown debugger error"
    }
}
