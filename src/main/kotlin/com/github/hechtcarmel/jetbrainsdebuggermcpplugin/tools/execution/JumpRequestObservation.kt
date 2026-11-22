package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Tracks invocation, not backend dispatch: even a normally returning method can be a no-op. */
internal class JumpRequestObservation<T : Any> {
    enum class Invocation { QUEUED, STARTED, RETURNED }

    private val invocation = AtomicReference(Invocation.QUEUED)
    private val reply = CompletableDeferred<T>()

    fun invocationStarted() { invocation.set(Invocation.STARTED) }
    fun invocationReturned() { invocation.set(Invocation.RETURNED) }
    fun complete(value: T) { reply.complete(value) }

    suspend fun await(
        timeoutMs: Long,
        accepted: T,
        confirmsTarget: suspend () -> Boolean,
        unverified: (Invocation) -> T,
    ): T {
        val result = withTimeoutOrNull(timeoutMs) {
            JumpTargetConfirmation.awaitReplyOrTarget(reply, accepted) {
                invocation.get() != Invocation.QUEUED && confirmsTarget()
            }
        }
        // Preserve a reply delivered at the timeout boundary. Nothing can revise a response after
        // it has returned; an unverified outcome makes no claim about later completion/errors.
        return result ?: if (reply.isCompleted) reply.await() else unverified(invocation.get())
    }

    companion object {
        fun unverifiedMessage(invocation: Invocation): String {
            val stage = when (invocation) {
                Invocation.QUEUED -> "The jump invocation was queued but had not started when the observation ended; it may still start later."
                Invocation.STARTED -> "The debugger's jump method was invoked once, but had not returned when the observation ended."
                Invocation.RETURNED -> "The debugger's jump method was invoked once and returned; that does not confirm that it dispatched or applied the jump."
            }
            return "$stage Completion could not be verified: the jump may have happened, may be pending, " +
                "or may not have been applied. Do not automatically retry. Inspect the requested session " +
                "with get_debug_session_status before deciding what to do next; a matching line alone " +
                "does not confirm completion on the same thread."
        }
    }
}
