package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.JumpRequestObservation.Invocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production callback adapter and bounded observer, without an IDE or DAP stubs. */
class JumpRequestObservationTest {
    private sealed interface Result {
        data object Confirmed : Result
        data class Failed(val reason: String) : Result
        data class Unverified(val invocation: Invocation) : Result
    }

    interface LegacyCallback {
        fun ok(value: Any?)
        fun error(exception: Exception)
    }

    interface UnknownCallback { fun something(value: String) }

    private fun callback(state: JumpRequestObservation<Result>): Any = JumpRequestCallbacks.create(
        Function1::class.java,
        onOk = { fail("An error-only Kotlin callback cannot acknowledge success") },
        onError = { fail("An error-only Kotlin callback is not the legacy Throwable callback") },
        onErrorMessage = { state.complete(Result.Failed(it)) },
    )

    private suspend fun outcome(state: JumpRequestObservation<Result>, confirms: suspend () -> Boolean = { false }): Result =
        state.await(150, Result.Confirmed, confirms) { Result.Unverified(it) }

    @Test fun `Kotlin callback has erased invoke Object to Object signature and returns Unit`() {
        val state = JumpRequestObservation<Result>()
        val invoke = Function1::class.java.getMethod("invoke", Any::class.java)
        assertEquals(Any::class.java, invoke.returnType)
        assertSame(Unit, invoke.invoke(callback(state), "backend refusal"))
        assertEquals(Result.Failed("backend refusal"), runBlocking { outcome(state) })
    }

    @Test fun `ordinary Kotlin caller invokes actual function adapter with String and Unit`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        @Suppress("UNCHECKED_CAST")
        val onError = callback(state) as (String) -> Unit
        assertSame(Unit, onError("exact backend reason"))
        assertEquals(Result.Failed("exact backend reason"), outcome(state))
    }

    @Test fun `asynchronous error after normal method return is still authoritative`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        val onError = callback(state)
        state.invocationReturned()
        val job = launch(Dispatchers.Default) {
            delay(20)
            Function1::class.java.getMethod("invoke", Any::class.java).invoke(onError, "asynchronous refusal")
        }
        try {
            val result = state.await(2_000, Result.Confirmed, { false }) { Result.Unverified(it) }
            assertEquals(Result.Failed("asynchronous refusal"), result)
        } finally {
            job.join()
        }
    }

    @Test fun `asynchronous error delivered during position sampling wins`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        val onError = callback(state)
        assertEquals(Result.Failed("refused while observing"), outcome(state) {
            Function1::class.java.getMethod("invoke", Any::class.java).invoke(onError, "refused while observing")
            true
        })
    }

    @Test fun `legacy ok and error callbacks still forward their original arguments`() {
        val response = Any()
        val exception = IllegalStateException("original exception")
        var ok: Any? = null
        var error: Throwable? = null
        val adapter = JumpRequestCallbacks.create(LegacyCallback::class.java,
            onOk = { ok = it }, onError = { error = it }, onErrorMessage = { fail("Wrong callback contract") }) as LegacyCallback
        adapter.ok(response)
        adapter.error(exception)
        assertSame(response, ok)
        assertSame(exception, error)
    }

    @Test fun `an unknown callback interface is unsupported rather than silently discarded`() {
        assertFalse(JumpRequestCallbacks.supports(UnknownCallback::class.java))
        assertFalse(JumpRequestCallbacks.supports(Any::class.java))
        assertTrue(JumpRequestCallbacks.supports(Function1::class.java))
        assertTrue(JumpRequestCallbacks.supports(LegacyCallback::class.java))
    }

    @Test fun `queued request cannot use an unrelated target observation as proof`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        var sampled = false
        assertEquals(Result.Unverified(Invocation.QUEUED), outcome(state) { sampled = true; true })
        assertFalse(sampled)
        assertTrue(JumpRequestObservation.unverifiedMessage(Invocation.QUEUED).contains("may still start later"))
    }

    @Test fun `a still running invocation is unverified not a failed or completed jump`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        assertEquals(Result.Unverified(Invocation.STARTED), outcome(state))
    }

    @Test fun `normal void return and silent no op do not confirm dispatch or success`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        state.invocationReturned()
        assertEquals(Result.Unverified(Invocation.RETURNED), outcome(state))
        assertTrue(JumpRequestObservation.unverifiedMessage(Invocation.RETURNED).contains("does not confirm that it dispatched or applied"))
    }

    @Test fun `different thread at the exact target remains unverified`() = runBlocking {
        val target = JumpTargetConfirmation.Location("file:///benign.py", 4)
        val before = JumpTargetConfirmation.Snapshot(Any(), Any(), Any(), target.copy(line = 2), true)
        val otherThreadAtTarget = before.copy(thread = Any(), location = target)
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        state.invocationReturned()
        assertEquals(Result.Unverified(Invocation.RETURNED), outcome(state) {
            JumpTargetConfirmation.confirmsMove(before, otherThreadAtTarget, target)
        })
    }

    @Test fun `unknown thread and unchanged pause remain unverified`() = runBlocking {
        val target = JumpTargetConfirmation.Location("file:///benign.py", 4)
        val before = JumpTargetConfirmation.Snapshot(Any(), Any(), Any(), target.copy(line = 2), true)
        for (after in listOf(before, before.copy(thread = null, location = target))) {
            val state = JumpRequestObservation<Result>()
            state.invocationStarted()
            state.invocationReturned()
            assertEquals(Result.Unverified(Invocation.RETURNED), outcome(state) {
                JumpTargetConfirmation.confirmsMove(before, after, target)
            })
        }
    }

    @Test fun `confirmed same thread changed target still succeeds without a success callback`() = runBlocking {
        val target = JumpTargetConfirmation.Location("file:///benign.py", 4)
        val before = JumpTargetConfirmation.Snapshot(Any(), Any(), Any(), target.copy(line = 2), true)
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        state.invocationReturned()
        assertEquals(Result.Confirmed, outcome(state) {
            JumpTargetConfirmation.confirmsMove(before, before.copy(location = target), target)
        })
    }

    @Test fun `a known accepted baseline reply is preserved`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.complete(Result.Confirmed)
        assertEquals(Result.Confirmed, outcome(state) { fail("Completed reply must win"); false })
    }

    @Test fun `a late failure cannot revise an already returned unverified outcome`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        state.invocationReturned()
        val result = outcome(state)
        state.complete(Result.Failed("late error"))
        assertEquals(Result.Unverified(Invocation.RETURNED), result)
    }

    @Test fun `cancellation propagates rather than becoming unverified`() = runBlocking {
        val state = JumpRequestObservation<Result>()
        state.invocationStarted()
        val cancelled = CancellationException("caller cancelled")
        try {
            outcome(state) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("caller cancelled", e.message)
        }
    }

    @Test fun `all ambiguous stages explicitly preclude an automatic retry or presumed same thread`() {
        for (stage in Invocation.entries) {
            val message = JumpRequestObservation.unverifiedMessage(stage)
            assertTrue(message.contains("Completion could not be verified"))
            assertTrue(message.contains("may not have been applied"))
            assertTrue(message.contains("Do not automatically retry"))
            assertTrue(message.contains("get_debug_session_status"))
            assertTrue(message.contains("does not confirm completion on the same thread"))
        }
    }
}
