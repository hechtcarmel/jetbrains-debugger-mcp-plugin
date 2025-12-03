package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Utility class for stack frame operations.
 */
object StackFrameUtils {

    private val CLASS_NAME_REGEX = Regex("""([a-zA-Z_][\w.]*)\.[a-zA-Z_]\w*\(""")
    private val METHOD_NAME_REGEX = Regex("""\.([a-zA-Z_]\w*)\(""")

    /**
     * Extracts the class name from a stack frame's string representation.
     */
    fun extractClassName(frame: XStackFrame): String? {
        val presentation = frame.toString()
        val match = CLASS_NAME_REGEX.find(presentation)
        return match?.groupValues?.get(1)
    }

    /**
     * Extracts the method name from a stack frame's string representation.
     */
    fun extractMethodName(frame: XStackFrame): String? {
        val presentation = frame.toString()
        val match = METHOD_NAME_REGEX.find(presentation)
        return match?.groupValues?.get(1)
    }

    /**
     * Gets a stack frame at a specific index from a debug session.
     * Returns null if the frame is not found or timeout occurs.
     *
     * @param session The debug session
     * @param frameIndex The index of the frame (0 = top frame)
     * @param timeoutMs Timeout in milliseconds (default 3000)
     */
    suspend fun getFrameAtIndex(
        session: XDebugSession,
        frameIndex: Int,
        timeoutMs: Long = 3000L
    ): XStackFrame? {
        val suspendContext = session.suspendContext ?: return null
        val executionStack = suspendContext.activeExecutionStack ?: return null

        if (frameIndex == 0) {
            return executionStack.topFrame
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val frames = mutableListOf<XStackFrame>()

                executionStack.topFrame?.let { frames.add(it) }

                executionStack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        frames.addAll(stackFrames)
                        if (last || frames.size > frameIndex) {
                            val result = frames.getOrNull(frameIndex)
                            continuation.resume(result)
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        continuation.resume(frames.getOrNull(frameIndex))
                    }
                })
            }
        }
    }

    /**
     * Collects stack frames from an execution stack up to a specified limit.
     *
     * @param executionStack The execution stack to collect frames from
     * @param limit Maximum number of frames to collect
     * @param timeoutMs Timeout in milliseconds (default 3000)
     */
    suspend fun collectStackFrames(
        executionStack: XExecutionStack,
        limit: Int,
        timeoutMs: Long = 3000L
    ): List<XStackFrame> {
        val frames = mutableListOf<XStackFrame>()

        executionStack.topFrame?.let { frames.add(it) }

        if (limit <= 1) {
            return frames.take(limit)
        }

        val additionalFrames = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<List<XStackFrame>> { continuation ->
                val collectedFrames = mutableListOf<XStackFrame>()

                executionStack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        collectedFrames.addAll(stackFrames)
                        if (last || collectedFrames.size >= limit - 1) {
                            continuation.resume(collectedFrames.take(limit - 1))
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        continuation.resume(collectedFrames)
                    }
                })
            }
        } ?: emptyList()

        frames.addAll(additionalFrames)
        return frames.take(limit)
    }

    /**
     * Checks if a file path indicates a library file.
     */
    fun isLibraryPath(path: String?): Boolean {
        if (path == null) return false
        return path.contains(".jar!") || path.contains("/jdk/")
    }
}
