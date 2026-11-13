package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.OutputStream

class RunSessionServiceTest : BasePlatformTestCase() {
    private lateinit var service: RunSessionService
    private val handlers = mutableListOf<Handler>()

    override fun setUp() {
        super.setUp()
        service = RunSessionService(project)
        Disposer.register(testRootDisposable, service)
    }

    override fun tearDown() {
        try {
            handlers.filterNot { it.isProcessTerminated }.forEach { it.finish(0) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    private inner class Handler : ProcessHandler() {
        init { handlers.add(this) }
        var stopCount = 0
        override fun destroyProcessImpl() { stopCount++; notifyProcessTerminated(143) }
        override fun detachProcessImpl() { notifyProcessDetached() }
        override fun detachIsDefault() = false
        override fun getProcessInput(): OutputStream? = null
        fun finish(code: Int) = notifyProcessTerminated(code)
    }

    private fun environment(name: String): ExecutionEnvironment {
        val profile = object : RunProfile {
            override fun getName() = name
            override fun getIcon(): javax.swing.Icon? = null
            override fun getState(executor: com.intellij.execution.Executor, environment: ExecutionEnvironment): RunProfileState? = null
        }
        val runner = object : ProgramRunner<RunnerSettings> {
            override fun getRunnerId() = "run-session-test"
            override fun canRun(executorId: String, profile: RunProfile) = true
            override fun execute(environment: ExecutionEnvironment) {}
        }
        return ExecutionEnvironmentBuilder(project, DefaultRunExecutor.getRunExecutorInstance())
            .runProfile(profile).runner(runner).build().apply { assignNewExecutionId() }
    }

    private fun console(text: String): ConsoleView {
        val view = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(testRootDisposable, view)
        view.component
        view.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (view.hasDeferredOutput() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertFalse(view.hasDeferredOutput())
        return view
    }

    private fun start(name: String, text: String): Triple<String, Handler, RunContentDescriptor> {
        val env = environment(name)
        val id = service.scheduled(env)!!
        val handler = Handler().apply { startNotify() }
        val view = console(text)
        val descriptor = RunContentDescriptor(view, handler, view.component, name)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        Disposer.register(testRootDisposable) {
            RunContentManager.getInstance(project).removeRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        }
        service.started(env, handler)
        return Triple(id, handler, descriptor)
    }

    fun `test exact running and terminated session pages survive without a duplicate output buffer`() {
        val (id, handler, descriptor) = start("one", "one\ntwo\nthree\n")
        val (otherId, otherHandler) = start("two", "foreign output\n")
        assertTrue(id.startsWith("run:"))
        assertFalse(id == otherId)
        val page = service.read(id, 1, 1, 100).console!!
        assertEquals("two\n", page.output)
        assertEquals(3, page.totalLines)
        assertEquals("running", service.session(id).state)
        handler.finish(7)
        assertEquals("terminated", service.session(id).state)
        assertEquals(7, service.session(id).exitCode)
        assertEquals("three\n", service.read(id, 1, 0, 100).console!!.output)
        assertEquals(0, otherHandler.stopCount)
        assertEquals("not_running", service.stop(id).status)
        val console = descriptor.executionConsole as ConsoleView
        console.clear()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("", service.read(id, 100, 0, 100).console!!.output)
    }

    fun `test stop affects only selected process and Debug ids are rejected`() {
        val (id, first) = start("same name", "first\n")
        val (_, second) = start("same name", "second\n")
        assertEquals("stopping", service.stop(id).status)
        assertEquals(1, first.stopCount)
        assertEquals(0, second.stopCount)
        try { service.stop("debug-uuid"); fail("Expected Run id rejection") }
        catch (e: ToolExecutionError) { assertTrue(e.message!!.contains("Run session ID")) }
    }

    fun `test reused tab cannot supply newer output to an old id`() {
        val (id, first, descriptor) = start("reuse", "old\n")
        first.finish(0)
        descriptor.processHandler = Handler().apply { startNotify() }
        val result = service.read(id, 100, 0, 100)
        assertNull(result.console)
        assertNotNull(result.consoleError)
    }

    fun `test failure metadata is bounded and no process is not successful startup`() {
        val env = environment("failed")
        val id = service.scheduled(env)!!
        service.failed(env, "failure".repeat(1000))
        val result = service.read(id, 100, 0, 100)
        assertEquals("failed_start", result.session.state)
        assertEquals(RunSessionService.MAX_DIAGNOSTIC_CHARS, result.session.failureMessage!!.length)
        assertNull(result.console)
        assertNotNull(result.consoleError)
        assertEquals("not_running", service.stop(id).status)
    }

    fun `test metadata cap evicts oldest finished record but preserves active records`() {
        val active = service.reserve("still starting")
        val old = service.reserve("old failed")
        service.failed(old, "failed")
        repeat(RunSessionService.MAX_SESSIONS - 2) { service.reserve("pending $it") }
        service.reserve("new")
        assertEquals(RunSessionService.MAX_SESSIONS, service.list().sessions.size)
        assertEquals("starting", service.session(active).state)
        assertFalse(service.list().sessions.any { it.id == old })
        try { service.reserve("would exceed cap"); fail("No unbounded metadata") }
        catch (e: ToolExecutionError) { assertTrue(e.message!!.contains("No configuration was launched")) }
    }

    fun `test failed start reads explicitly bound retained console without a process`() {
        val env = environment("failed with console")
        val id = service.scheduled(env)!!
        val view = console("startup diagnostic\n")
        val descriptor = RunContentDescriptor(view, null, view.component, "failed").apply { executionId = env.executionId }
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        Disposer.register(testRootDisposable) {
            RunContentManager.getInstance(project).removeRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        }
        service.failed(env, "Cannot create process")
        assertNull("Execution IDs alone are not exact ownership", service.read(id, 100, 0, 100).console)
        service.bindDescriptor(id, descriptor)
        assertEquals("startup diagnostic\n", service.read(id, 100, 0, 100).console!!.output)
        assertEquals("failed_start", service.session(id).state)
        descriptor.executionId++
        assertNull(service.read(id, 100, 0, 100).console)
    }

    fun `test process created before failed startup retains failed state and output`() {
        val env = environment("partially created")
        val handler = Handler()
        val id = service.scheduled(env)!!
        service.starting(env, handler)
        service.failed(env, "Runner failed")
        handler.startNotify()
        handler.finish(1)
        service.terminated(env, handler, 1)
        assertEquals("failed_start", service.session(id).state)
        assertEquals("Runner failed", service.session(id).failureMessage)
    }

    fun `test shared execution environment preserves separate ids per process`() {
        val env = environment("compound")
        val first = Handler().apply { startNotify() }
        val second = Handler().apply { startNotify() }
        service.started(env, first)
        service.started(env, second)
        val sessions = service.list().sessions
        assertEquals(2, sessions.size)
        service.stop(sessions[1].id)
        assertEquals(0, first.stopCount)
        assertEquals(1, second.stopCount)
    }

    fun `test content selection before started event preserves the reserved id`() {
        val env = environment("event order")
        val id = service.scheduled(env)!!
        val handler = Handler().apply { startNotify() }
        val view = console("owned output\n")
        val descriptor = RunContentDescriptor(view, handler, view.component, "event order")
        Disposer.register(testRootDisposable, descriptor)
        service.observed(descriptor)
        assertEquals(listOf(id), service.list().sessions.map { it.id })
        service.started(env, handler)
        service.observed(descriptor)
        assertEquals(listOf(id), service.list().sessions.map { it.id })
        assertEquals("owned output\n", service.read(id, 100, 0, 100).console!!.output)
    }

    fun `test execution listener captures asynchronous start failure`() {
        val listener = RunSessionExecutionListener()
        val env = environment("listener failure")
        listener.processStartScheduled(DefaultRunExecutor.EXECUTOR_ID, env)
        val id = env.getUserData(RunSessionService.SESSION_KEY)!!
        listener.processNotStarted(DefaultRunExecutor.EXECUTOR_ID, env, IllegalStateException("before launch task failed"))
        val session = RunSessionService.getInstance(project).session(id)
        assertEquals("failed_start", session.state)
        assertEquals("before launch task failed", session.failureMessage)
    }

    fun `test listener ignores Debug executor lifecycle`() {
        val listener = RunSessionExecutionListener()
        val before = RunSessionService.getInstance(project).list().sessions
        listener.processStartScheduled(DefaultDebugExecutor.EXECUTOR_ID, environment("debug"))
        assertEquals(before, RunSessionService.getInstance(project).list().sessions)
    }
}
