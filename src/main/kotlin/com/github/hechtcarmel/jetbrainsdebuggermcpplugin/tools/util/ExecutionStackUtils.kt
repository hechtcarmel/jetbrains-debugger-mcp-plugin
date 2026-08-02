package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Bridges XSuspendContext.computeExecutionStacks' callback API to coroutines.
 * The active execution stack is always first; on timeout or error the stacks
 * collected so far (at minimum the active one) are returned.
 */
object ExecutionStackUtils {

    private const val DEFAULT_TIMEOUT_MS = 3000L

    suspend fun collectExecutionStacks(
        suspendContext: XSuspendContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): List<XExecutionStack> {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val stacks = mutableListOf<XExecutionStack>()

                // Stacks arrive in batches; a misbehaving debug process calling back after
                // last=true (or erroring after completion) must not resume twice.
                val resumed = AtomicBoolean(false)
                continuation.invokeOnCancellation { resumed.set(true) }

                suspendContext.activeExecutionStack?.let { stacks.add(it) }

                suspendContext.computeExecutionStacks(object : XSuspendContext.XExecutionStackContainer {
                    override fun addExecutionStack(
                        executionStacks: MutableList<out XExecutionStack>,
                        last: Boolean
                    ) {
                        for (stack in executionStacks) {
                            if (!stacks.any { it.hashCode() == stack.hashCode() }) {
                                stacks.add(stack)
                            }
                        }
                        if (last && resumed.compareAndSet(false, true)) {
                            continuation.resume(stacks.toList())
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        if (resumed.compareAndSet(false, true)) {
                            continuation.resume(stacks.toList())
                        }
                    }
                })
            }
        } ?: listOfNotNull(suspendContext.activeExecutionStack)
    }
}
