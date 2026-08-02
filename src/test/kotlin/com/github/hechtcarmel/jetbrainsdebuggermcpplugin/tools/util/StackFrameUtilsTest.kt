package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StackFrameUtilsTest {

    private fun frame(): XStackFrame = object : XStackFrame() {}

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
