package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport.McpHttpTestCase
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig.RunSessionService
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig.StopRunSessionTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StableObjectIds
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.OutputStream

/** Exact target selection stays on EDT; handlers reject EDT/read-lock termination like Gradle. */
class StopSessionToolBehaviorTest : McpHttpTestCase() {
    private val handlers = mutableListOf<Handler>()

    private inner class Handler(
        val detachDefault: Boolean = false,
        val failure: RuntimeException? = null,
        var policyFailure: Boolean = false
    ) : ProcessHandler() {
        var destroyed = 0
        var detached = 0
        init { handlers.add(this); startNotify() }
        override fun destroyProcessImpl() {
            assertStopThread()
            destroyed++
            failure?.let { throw it }
            notifyProcessTerminated(0)
        }
        override fun detachProcessImpl() {
            assertStopThread()
            detached++
            notifyProcessDetached()
        }
        override fun detachIsDefault(): Boolean {
            check(ApplicationManager.getApplication().isDispatchThread) { "Select detach policy on EDT" }
            if (policyFailure) throw IllegalStateException("detach policy unavailable")
            return detachDefault
        }
        override fun getProcessInput(): OutputStream? = null
        fun finish() { if (!isProcessTerminated) notifyProcessTerminated(0) }
        private fun assertStopThread() {
            check(!ApplicationManager.getApplication().isDispatchThread) { "Gradle-style stop cannot run on EDT" }
            check(!ApplicationManager.getApplication().isReadAccessAllowed) { "Stop cannot hold a read lock" }
        }
    }

    override fun tearDown() {
        try {
            handlers.forEach { it.policyFailure = false; it.finish() }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }

    private fun console(): ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console.apply {
        Disposer.register(testRootDisposable, this)
        component
    }

    private fun runSession(handler: Handler, name: String): String {
        val view = console()
        val descriptor = RunContentDescriptor(view, handler, view.component, name)
        Disposer.register(testRootDisposable, descriptor)
        val service = RunSessionService.getInstance(project)
        service.observed(descriptor)
        return service.list().sessions.single { it.configurationName == name }.id
    }

    private fun debugSession(handler: Handler, name: String): XDebugSession {
        val view = console()
        return XDebuggerManager.getInstance(project).startSessionAndShowTab(name, null, true, object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess = object : XDebugProcess(session) {
                override fun getEditorsProvider(): XDebuggerEditorsProvider = object : XDebuggerEditorsProvider() {
                    override fun getFileType() = PlainTextFileType.INSTANCE
                }
                override fun createConsole(): ConsoleView = view
                override fun doGetProcessHandler(): ProcessHandler = handler
            }
        })
    }

    private fun call(tool: String, id: String) = pumpingEdt {
        post(McpConstants.MCP_ENDPOINT_PATH, toolCall(tool, """{"session_id":"$id"}"""))
    }.jsonBody()["result"]!!.jsonObject

    fun `test Run stop destroys only selected handler off EDT`() {
        val selected = Handler()
        val other = Handler()
        val id = runSession(selected, "selected Run")
        runSession(other, "other Run")
        val result = call("stop_run_session", id)
        assertFalse(result.toString(), result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(id, result["structuredContent"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals(1, selected.destroyed)
        assertEquals(0, other.destroyed)
    }

    fun `test Debug stop preserves detach default and exact session selection`() {
        val selected = Handler(detachDefault = true)
        val other = Handler()
        val session = debugSession(selected, "selected Debug")
        debugSession(other, "other Debug")
        val result = call("stop_debug_session", StableObjectIds.idFor(session))
        assertFalse(result.toString(), result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(1, selected.detached)
        assertEquals(0, selected.destroyed)
        assertEquals(0, other.destroyed)
    }

    fun `test Debug stop destroys a local process off EDT`() {
        val handler = Handler()
        val result = call("stop_debug_session", StableObjectIds.idFor(debugSession(handler, "local Debug")))
        assertFalse(result.toString(), result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(1, handler.destroyed)
        assertEquals(0, handler.detached)
    }

    fun `test stop errors remain tool errors for both modes`() {
        val run = Handler(failure = IllegalStateException("run stop failed"))
        val runResult = call("stop_run_session", runSession(run, "failing Run"))
        assertTrue(runResult["isError"]!!.jsonPrimitive.boolean)
        assertTrue(runResult["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("run stop failed"))
        val debug = Handler(failure = IllegalStateException("debug stop failed"))
        val debugResult = call("stop_debug_session", StableObjectIds.idFor(debugSession(debug, "failing Debug")))
        assertTrue(debugResult["isError"]!!.jsonPrimitive.boolean)
        assertTrue(debugResult["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("debug stop failed"))
    }

    fun `test stop cancellation propagates for both modes`() {
        for (debugMode in listOf(false, true)) {
            val cancellation = CancellationException("stop cancelled")
            val handler = Handler(failure = cancellation)
            val id = if (debugMode) StableObjectIds.idFor(debugSession(handler, "cancelled Debug"))
                else runSession(handler, "cancelled Run")
            try {
                pumpingEdt {
                    runBlocking {
                        val tool = if (debugMode) StopDebugSessionTool() else StopRunSessionTool()
                        tool.execute(project, buildJsonObject { put("session_id", id) })
                    }
                }
                fail("Cancellation must not become a successful or ordinary error result")
            } catch (actual: CancellationException) {
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
            }
        }
    }

    fun `test Debug target policy errors remain ordinary tool errors`() {
        val handler = Handler()
        val session = debugSession(handler, "bad policy Debug")
        handler.policyFailure = true
        val result = call("stop_debug_session", StableObjectIds.idFor(session))
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("detach policy unavailable"))
        assertEquals(0, handler.destroyed)
        assertEquals(0, handler.detached)
    }
}
