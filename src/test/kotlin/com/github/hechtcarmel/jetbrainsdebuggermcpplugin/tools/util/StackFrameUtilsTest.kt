package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StackFrameUtilsTest {

    private fun frame(): XStackFrame = object : XStackFrame() {}

    /**
     * A frame that renders a label the way the Frames panel does, while its toString() stays
     * the default identity hash. This is the shape Delve, and every other debugger that does
     * not override toString(), actually produces.
     */
    private fun renderingFrame(label: String): XStackFrame = object : XStackFrame() {
        override fun customizePresentation(component: ColoredTextContainer) {
            component.append(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    @Test
    fun `renderLabel returns what the frame renders, not its identity hash`() {
        val label = "demo.(*Pool).Run.func1 (pool.go:61) example.com/agentdebugdemo"

        val rendered = StackFrameUtils.renderLabel(renderingFrame(label))

        assertEquals(label, rendered)
        assertNotNull(rendered)
        assertFalse("a rendered label must not be an object hash", rendered!!.contains("@"))
    }

    @Test
    fun `renderLabel is null for a frame with no real label of its own`() {
        // A bare XStackFrame renders the platform's "<invalid frame>" placeholder rather than
        // nothing at all. Letting that through would replace a usable "file:line" presentation
        // with a string that says less than the line number did.
        assertNull(StackFrameUtils.renderLabel(frame()))
        assertNull(StackFrameUtils.renderLabel(renderingFrame("<invalid frame>")))
    }

    @Test
    fun `class and method are parsed from the rendered label, not toString`() {
        // Java-shaped rendering: the regexes can read it, and previously only worked because
        // some debuggers happen to put the same text in toString().
        val f = renderingFrame("com.example.Service.handle(Service.java:42)")

        assertEquals("com.example.Service", StackFrameUtils.extractClassName(f))
        assertEquals("handle", StackFrameUtils.extractMethodName(f))
    }

    @Test
    fun `a location already present in the label is not appended twice`() {
        // Delve renders "pkg.Func (file.go:12) module" - the location is part of the label.
        // Appending it again produced "... module (pool.go:61)", which reads like two frames.
        val label = "demo.(*Pool).Run.func1 (pool.go:61) example.com/agentdebugdemo"

        val combined = StackFrameUtils.combineLabelAndLocation(label, "pool.go:61")

        assertEquals(label, combined)
        assertEquals(1, combined.split("pool.go:61").size - 1)
    }

    @Test
    fun `a label without the location gets it appended once`() {
        val combined = StackFrameUtils.combineLabelAndLocation("MyClass.handle", "Foo.java:10")

        assertEquals("MyClass.handle (Foo.java:10)", combined)
    }

    @Test
    fun `no label leaves the bare location`() {
        assertEquals("Foo.java:10", StackFrameUtils.combineLabelAndLocation(null, "Foo.java:10"))
        assertEquals("Foo.java:10", StackFrameUtils.combineLabelAndLocation("  ", "Foo.java:10"))
    }

    @Test
    fun `a frame whose rendering is not Class-dot-method still keeps its function name`() {
        // Go renders "pkg.(*Type).Method (file.go:12) module" - no "Class.method(" to match.
        // The old code fell through to a bare "file:line" and dropped the function entirely.
        val f = renderingFrame("demo.(*Pool).Run.func1 (pool.go:61) example.com/agentdebugdemo")

        val presentation = StackFrameUtils.formatPresentation(f)

        assertTrue(
            "expected the rendered label to survive, got: $presentation",
            presentation.contains("demo.(*Pool).Run.func1"),
        )
    }


    /**
     * Delivers the given batches synchronously, exactly the way a batching debugger does:
     * last=false for every batch but the final one. Synchronous delivery matters — a second
     * resume throws IllegalStateException on the delivering thread, so here it fails the test
     * instead of dying silently on a debugger thread.
     */
    private fun stackDelivering(
        topFrame: XStackFrame?,
        vararg batches: Pair<List<XStackFrame>, Boolean>,
        errorAfter: String? = null
    ): XExecutionStack = object : XExecutionStack("test-thread") {
        override fun getTopFrame(): XStackFrame? = topFrame
        override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
            batches.forEach { (frames, last) -> container.addStackFrames(frames, last) }
            errorAfter?.let { container.errorOccurred(it) }
        }
    }

    @Test
    fun `getFrameAtIndex resumes exactly once when the target frame arrives before the last batch`() = runBlocking {
        val top = frame()
        val second = frame()
        val third = frame()
        val session = FakeDebugSession().apply {
            fakeSuspendContext = suspendContextOf(
                stackDelivering(top, listOf(second) to false, listOf(third) to true)
            )
        }

        // The first batch already satisfies frames.size > frameIndex; the second batch
        // (last=true) re-triggers the resume condition and must be ignored, not throw.
        val result = StackFrameUtils.getFrameAtIndex(session, frameIndex = 1)

        assertSame(second, result)
    }

    @Test
    fun `collectStackFrames resumes exactly once when the limit is reached before the last batch`() = runBlocking {
        val top = frame()
        val second = frame()
        val third = frame()
        val stack = stackDelivering(top, listOf(second) to false, listOf(third) to true)

        val frames = StackFrameUtils.collectStackFrames(stack, limit = 2)

        assertEquals(listOf(top, second), frames)
    }

    @Test
    fun `collectStackFrames tolerates errorOccurred after the frames were already delivered`() = runBlocking {
        val top = frame()
        val second = frame()
        val stack = stackDelivering(top, listOf(second) to true, errorAfter = "process detached")

        val frames = StackFrameUtils.collectStackFrames(stack, limit = 5)

        assertEquals(listOf(top, second), frames)
    }

    @Test
    fun `collectStackFrames returns all frames when the last batch completes the stack`() = runBlocking {
        val top = frame()
        val second = frame()
        val third = frame()
        val stack = stackDelivering(top, listOf(second) to false, listOf(third) to true)

        val frames = StackFrameUtils.collectStackFrames(stack, limit = 10)

        assertEquals(listOf(top, second, third), frames)
    }

    @Test
    fun `isLibraryPath returns true for jar files`() {
        assertTrue(StackFrameUtils.isLibraryPath("/home/user/.m2/repository/org/example/lib.jar!/com/example/Class.class"))
        assertTrue(StackFrameUtils.isLibraryPath("C:/Users/user/.gradle/caches/modules/lib.jar!/org/foo/Bar.class"))
    }

    @Test
    fun `isLibraryPath returns true for jdk paths`() {
        assertTrue(StackFrameUtils.isLibraryPath("/usr/lib/jvm/jdk/lib/rt.jar"))
    }

    @Test
    fun `isLibraryPath returns false for project paths`() {
        assertFalse(StackFrameUtils.isLibraryPath("/home/user/project/src/main/java/com/example/Main.java"))
        assertFalse(StackFrameUtils.isLibraryPath("/Users/dev/myapp/src/App.kt"))
        assertFalse(StackFrameUtils.isLibraryPath("C:/Projects/webapp/src/Controller.java"))
    }

    @Test
    fun `isLibraryPath returns false for null path`() {
        assertFalse(StackFrameUtils.isLibraryPath(null))
    }

    @Test
    fun `isLibraryPath returns false for empty path`() {
        assertFalse(StackFrameUtils.isLibraryPath(""))
    }

    @Test
    fun `isLibraryPath is case sensitive for jar extension`() {
        assertTrue(StackFrameUtils.isLibraryPath("/path/to/lib.jar!/Class.class"))
        // Only lowercase .jar! is detected
        assertFalse(StackFrameUtils.isLibraryPath("/path/to/lib.JAR!/Class.class"))
    }

    @Test
    fun `isLibraryPath detects jdk in path`() {
        assertTrue(StackFrameUtils.isLibraryPath("/some/path/jdk/version/lib/src"))
    }
}
