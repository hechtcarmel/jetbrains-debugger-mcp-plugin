package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Unit-level coverage for SessionStatusCollector against a real XBreakpointManager and a
 * hand-rolled session/suspend-context. These tests pin pause-site semantics, stack/thread
 * collection and background-thread read access independently of the live debuggee suite.
 */
class SessionStatusCollectorTest : BasePlatformTestCase() {

    private lateinit var file: VirtualFile

    override fun setUp() {
        super.setUp()
        file = myFixture.addFileToProject(
            "Sample.java",
            """
            public class Sample {
                public static void main(String[] args) {
                    int total = 0;
                    for (int i = 0; i < 10; i++) {
                        total += i;
                    }
                    System.out.println(total);
                }
            }
            """.trimIndent()
        ).virtualFile
        removeAllBreakpoints()
    }

    override fun tearDown() {
        try {
            removeAllBreakpoints()
        } finally {
            super.tearDown()
        }
    }

    private fun breakpointManager() = XDebuggerManager.getInstance(project).breakpointManager

    private fun removeAllBreakpoints() {
        WriteAction.runAndWait<RuntimeException> {
            breakpointManager().allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .forEach { breakpointManager().removeBreakpoint(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addBreakpoint(line: Int, enabled: Boolean = true): XLineBreakpoint<*> {
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<XLineBreakpointType<*>>()
            .firstOrNull() as? XLineBreakpointType<XBreakpointProperties<*>>
            ?: error("No line breakpoint types registered in the test IDE")
        return WriteAction.compute<XLineBreakpoint<XBreakpointProperties<*>>, RuntimeException> {
            val breakpoint = breakpointManager().addLineBreakpoint(
                type, file.url, line, type.createBreakpointProperties(file, line)
            )
            breakpoint.isEnabled = enabled
            breakpoint
        }
    }

    private fun addTracepoint(line: Int): XLineBreakpoint<*> =
        addBreakpoint(line).also {
            WriteAction.runAndWait<RuntimeException> {
                it.suspendPolicy = com.intellij.xdebugger.breakpoints.SuspendPolicy.NONE
            }
        }

    private fun position(line: Int): XSourcePosition =
        requireNotNull(XDebuggerUtil.getInstance().createPosition(file, line))

    private fun frameAt(line: Int): XStackFrame = object : XStackFrame() {
        override fun getSourcePosition(): XSourcePosition? = position(line)
    }

    private fun pausedSession(topLine: Int, selectedLine: Int = topLine): FakeDebugSession =
        FakeDebugSession().apply {
            // Qualified: inside apply, a bare `project` is the fake's own (unsupported) getProject()
            fakeProject = this@SessionStatusCollectorTest.project
            fakePaused = true
            fakeTopFramePosition = position(topLine)
            fakeCurrentStackFrame = frameAt(selectedLine)
        }

    // ── Pause reason / breakpoint hit (C4) ──────────────────────────────────────────────

    fun `test breakpoint hit is computed from the pause site not the selected frame`() {
        addBreakpoint(line = 4)
        // select_stack_frame moved the current frame to a caller line with no breakpoint
        val session = pausedSession(topLine = 4, selectedLine = 1)

        assertEquals("breakpoint", SessionStatusCollector.determinePauseReason(session))
        val hit = SessionStatusCollector.getBreakpointHitInfo(session)
        assertNotNull("The pause-site breakpoint must still be reported after frame selection", hit)
        assertEquals(5, hit!!.line)
    }

    fun `test a breakpoint on the selected frame's line is not misreported as hit`() {
        addBreakpoint(line = 1)
        // Paused by a step at line 4; the selected caller frame happens to hold a breakpoint
        val session = pausedSession(topLine = 4, selectedLine = 1)

        assertEquals("step", SessionStatusCollector.determinePauseReason(session))
        assertNull(SessionStatusCollector.getBreakpointHitInfo(session))
    }

    fun `test a disabled breakpoint on the current line is not reported as hit`() {
        addBreakpoint(line = 4, enabled = false)
        val session = pausedSession(topLine = 4)

        assertEquals("step", SessionStatusCollector.determinePauseReason(session))
        assertNull(SessionStatusCollector.getBreakpointHitInfo(session))
    }

    fun `test no breakpoint is reported as hit while breakpoints are muted`() {
        addBreakpoint(line = 4)
        val session = pausedSession(topLine = 4).apply { fakeMuted = true }

        assertEquals("step", SessionStatusCollector.determinePauseReason(session))
        assertNull(SessionStatusCollector.getBreakpointHitInfo(session))
    }

    /**
     * A `suspend_policy: none` tracepoint logs without ever suspending, so it can never be the
     * cause of a pause. Before the filter, stepping onto a tracepoint's line reported
     * `pausedReason: "breakpoint"` with the tracepoint as the hit — live-QA finding 4.2.
     */
    fun `test a suspend-none tracepoint on the pause line is not reported as hit`() {
        addTracepoint(line = 4)
        val session = pausedSession(topLine = 4)

        assertNull(SessionStatusCollector.getBreakpointHitInfo(session))
        assertEquals("step", SessionStatusCollector.determinePauseReason(session))
    }

    // ── Frame presentation (live-QA 4.1) ────────────────────────────────────────────────

    /**
     * `presentation` must carry the same 1-based line as the machine-readable `line` field.
     * The platform's `XStackFrame.toString()` encodes a 0-based line, which shipped as an
     * off-by-one in every frame's prose — live-QA finding 4.1.
     */
    fun `test frame presentation uses the one-based line of the source position`() {
        val presentation = StackFrameUtils.formatPresentation(frameAt(line = 4))

        assertEquals("${file.name}:5", presentation)
    }

    fun `test frame presentation falls back to toString for frames without a position`() {
        val frame = object : XStackFrame() {
            override fun getSourcePosition(): XSourcePosition? = null
            override fun toString(): String = "native frame, position unknown"
        }

        assertEquals("native frame, position unknown", StackFrameUtils.formatPresentation(frame))
    }

    fun `test an enabled breakpoint on the pause line is reported with its condition`() {
        val breakpoint = addBreakpoint(line = 4)
        WriteAction.runAndWait<RuntimeException> {
            breakpoint.conditionExpression = XDebuggerUtil.getInstance()
                .createExpression("i == 3", null, null, EvaluationMode.EXPRESSION)
        }
        val session = pausedSession(topLine = 4)

        val hit = SessionStatusCollector.getBreakpointHitInfo(session)
        assertNotNull(hit)
        assertEquals("i == 3", hit!!.condition)
        assertEquals("line", hit.type)
    }

    // ── Real stack and thread data (C5) ─────────────────────────────────────────────────

    private fun stackOf(displayName: String, frames: List<XStackFrame>): XExecutionStack =
        object : XExecutionStack(displayName) {
            override fun getTopFrame(): XStackFrame? = frames.firstOrNull()
            override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
                container.addStackFrames(frames.drop(1), true)
            }
        }

    private fun collectStatus(session: FakeDebugSession, maxStackFrames: Int = 10) = runBlocking {
        SessionStatusCollector.collectStatus(
            project = project,
            session = session,
            includeVariables = false,
            includeSourceContext = false,
            maxStackFrames = maxStackFrames
        )
    }

    fun `test stack summary honours max_stack_frames and reports the fetched depth`() {
        val frames = List(5) { frameAt(it) }
        val session = pausedSession(topLine = 0).apply {
            fakeCurrentStackFrame = frames[0]
            fakeSuspendContext = suspendContextOf(stackOf("worker-1", frames))
        }

        val status = collectStatus(session, maxStackFrames = 3)

        assertEquals(3, status.stackSummary.size)
        assertEquals(3, status.totalStackDepth)
        assertEquals(listOf(0, 1, 2), status.stackSummary.map { it.index })
        assertEquals(listOf(1, 2, 3), status.stackSummary.map { it.line })
    }

    fun `test stack summary marks the selected frame as current`() {
        val frames = List(3) { frameAt(it) }
        val session = pausedSession(topLine = 0).apply {
            fakeCurrentStackFrame = frames[1]
            fakeSuspendContext = suspendContextOf(stackOf("worker-1", frames))
        }

        val status = collectStatus(session)

        assertEquals(listOf(false, true, false), status.stackSummary.map { it.isCurrent })
    }

    fun `test current thread comes from the active execution stack and threads are counted`() {
        val frames = listOf(frameAt(0))
        val session = pausedSession(topLine = 0).apply {
            fakeCurrentStackFrame = frames[0]
            fakeSuspendContext = suspendContextOf(
                stackOf("worker-1", frames),
                stackOf("worker-2", emptyList()),
                stackOf("worker-3", emptyList())
            )
        }

        val status = collectStatus(session)

        assertNotNull(status.currentThread)
        assertEquals("worker-1", status.currentThread!!.name)
        assertEquals("paused", status.currentThread!!.state)
        assertTrue(status.currentThread!!.isCurrent)
        assertEquals(3, status.threadCount)
    }

    // Mapped JVM positions can touch PSI/documents from getSourcePosition/getLine. Plain fake
    // positions and EDT-only fixture tests mask missing read actions (issue #97).
    private fun readCheckedPosition(line: Int): XSourcePosition {
        val delegate = position(line)
        return object : XSourcePosition by delegate {
            override fun getFile(): VirtualFile {
                check(ApplicationManager.getApplication().isReadAccessAllowed)
                return delegate.file
            }

            override fun getLine(): Int {
                check(ApplicationManager.getApplication().isReadAccessAllowed)
                return delegate.line
            }
        }
    }

    private fun readCheckedFrame(position: XSourcePosition): XStackFrame = object : XStackFrame() {
        override fun getSourcePosition(): XSourcePosition {
            check(ApplicationManager.getApplication().isReadAccessAllowed)
            return position
        }

        override fun computeChildren(node: XCompositeNode) {
            check(!ApplicationManager.getApplication().isReadAccessAllowed) {
                "Asynchronous variable collection must not hold a read lock"
            }
            node.addChildren(XValueChildrenList.EMPTY, true)
        }
    }

    private fun <T> inBackground(block: suspend () -> T): T {
        // Pump the fixture's EDT while a coroutine read action yields to pending IDE writes.
        val future = CompletableFuture.supplyAsync {
            runBlocking(Dispatchers.Default) {
                check(!ApplicationManager.getApplication().isDispatchThread)
                check(!ApplicationManager.getApplication().isReadAccessAllowed)
                withTimeout(10_000) { block() }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!future.isDone && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        return try {
            future.get(1, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            future.cancel(true)
        }
    }

    fun `test background stack summary reads mapped positions under read access`() {
        val frame = readCheckedFrame(readCheckedPosition(4))
        val session = FakeDebugSession().apply {
            fakeProject = this@SessionStatusCollectorTest.project
            // No selected frame or top position: exercise buildStackSummary independently.
            fakeSuspendContext = suspendContextOf(stackOf("worker", listOf(frame)))
        }

        val status = inBackground {
            SessionStatusCollector.collectStatus(project, session, includeVariables = false, includeSourceContext = false)
        }

        assertEquals(5, status.stackSummary.single().line)
        assertEquals(file.path, status.stackSummary.single().file)
        assertEquals("${file.name}:5", status.stackSummary.single().presentation)
    }

    fun `test background status snapshots source and breakpoints without locking callbacks`() {
        addBreakpoint(4)
        val position = readCheckedPosition(4)
        val frame = readCheckedFrame(position)
        val stack = object : XExecutionStack("worker") {
            override fun getTopFrame(): XStackFrame = frame
            override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
                check(!ApplicationManager.getApplication().isReadAccessAllowed) {
                    "Asynchronous frame collection must not hold a read lock"
                }
                container.addStackFrames(emptyList(), true)
            }
        }
        val session = pausedSession(4).apply {
            fakeTopFramePosition = position
            fakeCurrentStackFrame = frame
            fakeSuspendContext = suspendContextOf(stack)
        }

        val status = inBackground { SessionStatusCollector.collectStatus(project, session, sourceContextLines = 1) }

        assertEquals("breakpoint", status.pausedReason)
        assertEquals(5, status.breakpointHit!!.line)
        assertEquals(5, status.currentLocation!!.line)
        val source = requireNotNull(status.sourceContext)
        assertEquals(file.path, source.file)
        assertEquals(5, source.currentLine)
        assertEquals(listOf(4, 5, 6), source.lines.map { it.number })
        assertTrue(source.lines.single { it.isCurrent }.content.contains("total += i"))
        assertEquals(listOf(5), source.breakpointsInView)
        assertTrue(status.variables.isEmpty())
    }

    fun `test background pause helpers independently protect mapped positions`() {
        addBreakpoint(4)
        val session = pausedSession(4).apply { fakeTopFramePosition = readCheckedPosition(4) }

        val (reason, hit) = inBackground {
            SessionStatusCollector.determinePauseReason(session) to SessionStatusCollector.getBreakpointHitInfo(session)
        }

        assertEquals("breakpoint", reason)
        assertEquals(5, hit!!.line)
        assertEquals(file.path, hit.file)
    }

    fun `test background source mapping cancellation propagates`() {
        val delegate = position(4)
        val position = object : XSourcePosition by delegate {
            override fun getLine(): Int = throw CancellationException("mapping cancelled")
        }
        val session = pausedSession(4).apply {
            fakeTopFramePosition = null
            fakeCurrentStackFrame = readCheckedFrame(position)
        }

        try {
            inBackground { SessionStatusCollector.collectStatus(project, session, includeVariables = false) }
            fail("Cancellation must not turn into a partial successful status")
        } catch (e: CancellationException) {
            assertEquals("mapping cancelled", e.message)
        }
    }

    // ── Running-session gating (C13) ────────────────────────────────────────────────────

    fun `test a running session reports no location stack or threads`() {
        val session = FakeDebugSession().apply {
            fakeProject = this@SessionStatusCollectorTest.project
            fakePaused = false
            // A stale frame from the last pause must not leak into the running status
            fakeCurrentStackFrame = frameAt(4)
        }

        val status = collectStatus(session)

        assertEquals("running", status.state)
        assertNull(status.pausedReason)
        assertNull(status.currentLocation)
        assertNull(status.breakpointHit)
        assertNull(status.currentThread)
        assertEquals(0, status.totalStackDepth)
        assertTrue(status.stackSummary.isEmpty())
        assertEquals(0, status.threadCount)
    }
}
