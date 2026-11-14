package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.OutputStream

class ProcessTerminationTest : BasePlatformTestCase() {
    private class Handler(private val failure: RuntimeException? = null) : ProcessHandler() {
        var destroyed = 0
        var detached = 0
        override fun destroyProcessImpl() {
            assertBackgroundWithoutReadLock()
            destroyed++
            failure?.let { throw it }
            notifyProcessTerminated(0)
        }
        override fun detachProcessImpl() {
            assertBackgroundWithoutReadLock()
            detached++
            notifyProcessDetached()
        }
        override fun detachIsDefault() = false
        override fun getProcessInput(): OutputStream? = null
        fun finish() { if (!isProcessTerminated) notifyProcessTerminated(0) }
        private fun assertBackgroundWithoutReadLock() {
            check(!ApplicationManager.getApplication().isDispatchThread) { "stop on EDT" }
            check(!ApplicationManager.getApplication().isReadAccessAllowed) { "stop in read action" }
        }
    }

    fun `test destruction and detachment execute off EDT without read access`() {
        for (detach in listOf(false, true)) {
            val handler = Handler().apply { startNotify() }
            runBlocking { ProcessTermination.request(handler, detach) }
            assertEquals(if (detach) 0 else 1, handler.destroyed)
            assertEquals(if (detach) 1 else 0, handler.detached)
        }
    }

    fun `test pre start stop is rejected without queuing destruction on a later EDT start`() {
        val handler = Handler()
        try {
            try {
                runBlocking { ProcessTermination.request(handler) }
                fail("A pre-start stop must not be queued onto the startNotify caller")
            } catch (e: ToolExecutionError) {
                assertTrue(e.message.contains("not started yet"))
            }
            handler.startNotify() // EDT: any accidentally queued destruction fails the thread check.
            assertEquals(0, handler.destroyed)
            assertEquals(0, handler.detached)
        } finally { handler.finish() }
    }

    fun `test stop exceptions propagate without reporting successful termination`() {
        val expected = IllegalStateException("cancel failed")
        val handler = Handler(expected).apply { startNotify() }
        try {
            try { runBlocking { ProcessTermination.request(handler) }; fail("Expected failure") }
            catch (actual: IllegalStateException) {
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === expected })
            }
            assertFalse(handler.isProcessTerminated)
        } finally { handler.finish() }
    }

    fun `test cancellation remains cancellation`() {
        val expected = CancellationException("cancelled stop")
        val handler = Handler(expected).apply { startNotify() }
        try {
            try { runBlocking { ProcessTermination.request(handler) }; fail("Expected cancellation") }
            catch (actual: CancellationException) {
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === expected })
            }
            assertFalse(handler.isProcessTerminated)
        } finally { handler.finish() }
    }
}
