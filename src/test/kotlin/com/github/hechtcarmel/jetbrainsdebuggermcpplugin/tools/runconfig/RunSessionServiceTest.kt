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
        Disposer.register(testRootDisposable, descriptor)
        service.started(env, handler)
        // showRunContent is a no-op in IDE unit-test mode. Simulate the exact
        // descriptor binding performed by the production runner callback.
        service.bindDescriptor(id, descriptor)
        return Triple(id, handler, descriptor)
    }

    fun `test exact running and terminated session pages survive without a duplicate output buffer`() {
        val (id, handler, descriptor) = start("one", "one\ntwo\nthree\n")
        val (otherId, otherHandler) = start("two", "foreign output\n")
        assertTrue(id.startsWith("run:"))
        assertFalse(id == otherId)
        val result = service.read(id, 1, 1, 100)
        assertNotNull(result.consoleError, result.console)
        val page = result.console!!
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
        // Clearing ConsoleView is asynchronous, just like rendering new output.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (service.read(id, 100, 0, 100).console!!.output.isNotEmpty() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
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
        Disposer.register(testRootDisposable, descriptor)
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
        val descriptor = RunContentDescriptor(view, handler, view.component, "event order").apply {
            executionId = env.executionId
        }
        Disposer.register(testRootDisposable, descriptor)
        service.observed(descriptor)
        assertEquals(listOf(id), service.list().sessions.map { it.id })
        service.started(env, handler)
        service.observed(descriptor)
        assertEquals(listOf(id), service.list().sessions.map { it.id })
        assertEquals("owned output\n", service.read(id, 100, 0, 100).console!!.output)
    }

    fun `test pre existing observed process keeps its id on termination event`() {
        val env = environment("pre existing")
        val handler = Handler().apply { startNotify() }
        val view = console("retained\n")
        val descriptor = RunContentDescriptor(view, handler, view.component, "pre existing")
        Disposer.register(testRootDisposable, descriptor)
        service.observed(descriptor)
        val observed = service.list().sessions.single().id
        handler.finish(9)
        service.terminated(env, handler, 9)
        assertEquals(listOf(observed), service.list().sessions.map { it.id })
        assertEquals(9, service.session(observed).exitCode)
        assertEquals("retained\n", service.read(observed, 100, 0, 100).console!!.output)
    }

    fun `test callback only bound start notified process advances lifecycle`() {
        val id = service.reserve("callback only")
        val handler = Handler()
        val view = console("callback output\n")
        val descriptor = RunContentDescriptor(view, handler, view.component, "callback only")
        Disposer.register(testRootDisposable, descriptor)
        service.bindDescriptor(id, descriptor)
        assertEquals("starting", service.session(id).state)
        handler.startNotify()
        assertEquals("running", service.session(id).state)
        service.failed(id, "late startup failure")
        assertEquals("failed_start", service.session(id).state)
    }

    fun `test zero id early observation reconciles to reserved id without losing the observed id`() {
        val env = environment("legacy runner")
        val reserved = service.scheduled(env)!!
        val handler = Handler().apply { startNotify() }
        val view = console("legacy output\n")
        val descriptor = RunContentDescriptor(view, handler, view.component, "legacy")
        Disposer.register(testRootDisposable, descriptor)
        assertEquals(0L, descriptor.executionId)
        service.observed(descriptor)
        val provisional = service.list().sessions.single { it.id != reserved }.id
        service.started(env, handler)
        assertEquals(listOf(reserved), service.list().sessions.map { it.id })
        assertEquals("legacy output\n", service.read(reserved, 100, 0, 100).console!!.output)
        assertEquals(reserved, service.session(provisional).id)
        assertEquals(reserved, service.read(provisional, 100, 0, 100).console!!.sessionId)
        // Delayed callbacks may still carry the originally observed ID.
        service.failed(provisional, "late diagnostic")
        assertEquals("late diagnostic", service.session(reserved).failureMessage)
        env.putUserData(RunSessionService.SESSION_KEY, provisional)
        assertEquals(reserved, service.scheduled(env))
        service.bindDescriptor(provisional, descriptor)
        val stopped = service.stop(provisional)
        assertEquals(reserved, stopped.sessionId)
        assertEquals("stopping", stopped.status)
        assertEquals(1, handler.stopCount)
    }

    fun `test identity budget preserves active aliases and prunes them on canonical eviction`() {
        val otherEnv = environment("other live process")
        val otherHandler = Handler().apply { startNotify() }
        service.started(otherEnv, otherHandler)
        val otherId = service.list().sessions.single().id
        val handler = Handler().apply { startNotify() }
        val view = console("shared process\n")
        val descriptor = RunContentDescriptor(view, handler, view.component, "observed")
        Disposer.register(testRootDisposable, descriptor)
        service.observed(descriptor)
        val identities = mutableListOf(service.list().sessions.single { it.id != otherId }.id)
        repeat(RunSessionService.MAX_SESSIONS - 2) {
            val env = environment("same process callback $it")
            val id = service.scheduled(env)!!
            service.started(env, handler)
            identities.add(id)
        }
        assertEquals(2, service.list().sessions.size)
        identities.forEach { assertEquals(identities.last(), service.session(it).id) }
        assertNull(service.scheduled(environment("over identity capacity")))
        assertTrue(service.list().trackingLimitReached)
        identities.forEach { assertEquals(identities.last(), service.session(it).id) }
        handler.finish(0)
        service.reserve("replacement")
        identities.forEach {
            try { service.session(it); fail("Evicted aliases must not survive") }
            catch (e: ToolExecutionError) { assertTrue(e.message!!.contains("metadata evicted")) }
        }
        assertEquals("running", service.session(otherId).state)
        assertTrue("Missed launch warning is historical", service.list().trackingLimitReached)
    }

    fun `test unrelated pending start does not hide a selected existing Run session`() {
        val pending = service.scheduled(environment("pending runner"))!!
        val handler = Handler().apply { startNotify() }
        val view = console("existing output\n")
        val descriptor = RunContentDescriptor(view, handler, view.component, "existing")
        Disposer.register(testRootDisposable, descriptor)
        service.observed(descriptor)
        val sessions = service.list().sessions
        assertEquals(2, sessions.size)
        val existing = sessions.single { it.id != pending }
        assertEquals("existing output\n", service.read(existing.id, 100, 0, 100).console!!.output)
        assertEquals("starting", service.session(pending).state)
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
