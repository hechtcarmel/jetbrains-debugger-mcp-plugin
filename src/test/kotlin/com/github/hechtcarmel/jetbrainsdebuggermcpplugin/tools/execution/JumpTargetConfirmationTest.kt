package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.JumpTargetConfirmation.Location
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.JumpTargetConfirmation.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

/** No IDE fixture: drive the exact production acknowledgment/target race and identity guards. */
class JumpTargetConfirmationTest {
    private enum class Reply { ACCEPTED, REFUSED, FAILED }
    private data class ThreadId(val id: String)
    private val target = Location("file:///scratch/jump.py", 4)
    private val before = Snapshot(Any(), Any(), ThreadId("main"), target.copy(line = 2), paused = true)

    private fun moved() = before.copy(location = target)

    @Test
    fun `changed target on the same thread confirms even when its wrapper is recreated`() {
        assertTrue(JumpTargetConfirmation.confirmsMove(before, moved().copy(thread = ThreadId("main")), target))
    }

    @Test
    fun `unchanged stale pause is not success`() {
        assertFalse(JumpTargetConfirmation.confirmsMove(before, before, target))
        val alreadyAtTarget = before.copy(location = target)
        assertFalse(JumpTargetConfirmation.confirmsMove(alreadyAtTarget, alreadyAtTarget, target))
    }

    @Test
    fun `another session at the target is not success`() {
        assertFalse(JumpTargetConfirmation.confirmsMove(before, moved().copy(session = Any()), target))
    }

    @Test
    fun `replacement debug process at the target is not success`() {
        assertFalse(JumpTargetConfirmation.confirmsMove(before, moved().copy(process = Any()), target))
    }

    @Test
    fun `another thread at the target is not success`() {
        assertFalse(JumpTargetConfirmation.confirmsMove(before, moved().copy(thread = ThreadId("other")), target))
        assertFalse(JumpTargetConfirmation.confirmsMove(before, moved().copy(thread = null), target))
    }

    @Test
    fun `same file different line or same line different file is not success`() {
        assertFalse(JumpTargetConfirmation.confirmsMove(before, moved().copy(location = target.copy(line = 5)), target))
        assertFalse(JumpTargetConfirmation.confirmsMove(before, moved().copy(location = target.copy(file = "file:///other.py")), target))
    }

    @Test
    fun `running stopped or unavailable positions cannot confirm`() {
        for (state in listOf(moved().copy(paused = false), moved().copy(stopped = true), moved().copy(location = null))) {
            assertFalse(JumpTargetConfirmation.confirmsMove(before, state, target))
        }
        assertFalse(JumpTargetConfirmation.confirmsMove(before.copy(location = null), moved(), target))
        assertFalse(JumpTargetConfirmation.confirmsMove(before.copy(thread = null), moved(), target))
        assertFalse(JumpTargetConfirmation.confirmsMove(before.copy(paused = false), moved(), target))
    }

    @Test
    fun `actual target move completes without an acknowledgment or pause event`() = runBlocking {
        val reply = CompletableDeferred<Reply>() // The backend never invokes ok/error.
        var reads = 0
        val outcome = withTimeoutOrNull(2_000) {
            JumpTargetConfirmation.awaitReplyOrTarget(reply, Reply.ACCEPTED) {
                reads++
                JumpTargetConfirmation.confirmsMove(before, if (reads == 1) before else moved(), target)
            }
        }
        assertEquals(Reply.ACCEPTED, outcome)
        assertTrue(reads >= 2)
        assertFalse("Position confirmation must not fabricate a backend reply", reply.isCompleted)
    }

    @Test
    fun `no callback and no transition really time out`() = runBlocking {
        val reply = CompletableDeferred<Reply>()
        val outcome = withTimeoutOrNull(150) {
            JumpTargetConfirmation.awaitReplyOrTarget(reply, Reply.ACCEPTED) {
                JumpTargetConfirmation.confirmsMove(before, before, target)
            }
        }
        assertNull(outcome)
        assertFalse(reply.isCompleted)
    }

    @Test
    fun `unrelated and stale pauses do not turn a timeout into acceptance`() = runBlocking {
        val reply = CompletableDeferred<Reply>()
        val samples = listOf(before, moved().copy(session = Any()), moved().copy(thread = ThreadId("other")), before)
        var reads = 0
        val outcome = withTimeoutOrNull(250) {
            JumpTargetConfirmation.awaitReplyOrTarget(reply, Reply.ACCEPTED) {
                JumpTargetConfirmation.confirmsMove(before, samples[(reads++).coerceAtMost(samples.lastIndex)], target)
            }
        }
        assertNull(outcome)
        assertTrue(reads >= 2)
    }

    @Test
    fun `explicit accepted reply still uses the existing reply path`() = runBlocking {
        var sampled = false
        val result = JumpTargetConfirmation.awaitReplyOrTarget(CompletableDeferred(Reply.ACCEPTED), Reply.ACCEPTED) {
            sampled = true
            false
        }
        assertEquals(Reply.ACCEPTED, result)
        assertFalse(sampled)
    }

    @Test
    fun `explicit refusal and failure take precedence over the target fallback`() = runBlocking {
        for (reply in listOf(Reply.REFUSED, Reply.FAILED)) {
            val result = JumpTargetConfirmation.awaitReplyOrTarget(CompletableDeferred(reply), Reply.ACCEPTED) { true }
            assertEquals(reply, result)
        }
    }

    @Test
    fun `refusal delivered during the snapshot wins over observed target`() = runBlocking {
        val reply = CompletableDeferred<Reply>()
        val result = JumpTargetConfirmation.awaitReplyOrTarget(reply, Reply.ACCEPTED) {
            reply.complete(Reply.REFUSED)
            true
        }
        assertEquals(Reply.REFUSED, result)
    }

    @Test
    fun `cancellation from source mapping is not swallowed`() = runBlocking {
        try {
            JumpTargetConfirmation.awaitReplyOrTarget(CompletableDeferred<Reply>(), Reply.ACCEPTED) {
                throw CancellationException("cancelled snapshot")
            }
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("cancelled snapshot", e.message)
        }
    }
}
