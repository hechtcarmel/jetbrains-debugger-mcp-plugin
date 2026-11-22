package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay

/** Immutable coordinates: a debugger may mutate/reuse its source position and suspend context. */
internal object JumpTargetConfirmation {
    data class Location(val file: String, val line: Int)

    data class Snapshot(
        val session: Any,
        val process: Any,
        val thread: Any?,
        val location: Location?,
        val paused: Boolean,
        val stopped: Boolean = false,
    )

    /**
     * A missing callback/event is not success. Only a changed top-frame location at the exact
     * requested target in the same session/process/thread confirms it. Stack equality is not a
     * generic cross-pause thread-ID contract: unknown or unequal wrappers stay unverified.
     * Never substitute a UI display name (which need not be unique) or hash code for identity.
     */
    fun confirmsMove(before: Snapshot, current: Snapshot, target: Location): Boolean =
        before.paused && !before.stopped && current.paused && !current.stopped &&
            current.session === before.session && current.process === before.process &&
            before.thread != null && current.thread == before.thread &&
            before.location != null && before.location != target && current.location == target

    /** Runs inside the request's timeout. Observe state; never send the command a second time. */
    suspend fun <T> awaitReplyOrTarget(
        reply: Deferred<T>,
        accepted: T,
        confirmsTarget: suspend () -> Boolean,
    ): T {
        while (!reply.isCompleted) {
            if (confirmsTarget()) {
                // A refusal/failure delivered while sampling must not be replaced by success.
                return if (reply.isCompleted) reply.await() else accepted
            }
            delay(POLL_INTERVAL_MS)
        }
        return reply.await()
    }

    const val POLL_INTERVAL_MS = 50L
}
