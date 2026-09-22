package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.resume

/**
 * Utility class for stack frame operations.
 */
object StackFrameUtils {

    /**
     * What XStackFrame's own default rendering produces. It carries no information, and letting
     * it through would put "<invalid frame>" into presentations that used to read `file:line`.
     */
    private const val PLACEHOLDER_LABEL = "<invalid frame>"

    private val CLASS_NAME_REGEX = Regex("""([a-zA-Z_][\w.]*)\.[a-zA-Z_]\w*\(""")
    private val METHOD_NAME_REGEX = Regex("""\.([a-zA-Z_]\w*)\(""")

    /**
     * The label the IDE's Frames panel shows for this frame.
     *
     * `XStackFrame.toString()` is not a contract. Some debuggers happen to return something
     * like `com.example.Foo.bar(Foo.java:10)`, which the regexes below can parse; others
     * inherit the default and return an identity hash — Delve frames arrive as
     * `com.goide.dlv.DlvStackFrame@6a38cd63`. Parsing that yields null for both the class and
     * the method and a bare `file:line` presentation, so a Go, Rust or Python stack loses the
     * one thing a caller actually wants: the function name.
     *
     * `customizePresentation` is how the panel itself renders a frame, so it is the same text
     * for every language. Returns null when a frame renders nothing, leaving callers on their
     * existing fallbacks.
     */
    fun renderLabel(frame: XStackFrame): String? {
        val builder = StringBuilder()
        val collector = object : ColoredTextContainer {
            override fun append(fragment: String, attributes: SimpleTextAttributes) {
                builder.append(fragment)
            }

            override fun append(fragment: String, attributes: SimpleTextAttributes, tag: Any?) {
                builder.append(fragment)
            }

            override fun setIcon(icon: Icon?) = Unit

            override fun setToolTipText(text: String?) = Unit
        }
        // A frame is free to throw while rendering; a label is never worth failing a tool call.
        runCatching { frame.customizePresentation(collector) }
        return builder.toString().trim()
            .takeIf { it.isNotEmpty() && it != PLACEHOLDER_LABEL }
    }

    /** Text the name regexes run against: what the IDE renders, or toString() if it renders nothing. */
    private fun parseableText(frame: XStackFrame): String = renderLabel(frame) ?: frame.toString()

    /**
     * Extracts the class name from a stack frame's rendered presentation.
     */
    fun extractClassName(frame: XStackFrame): String? {
        val match = CLASS_NAME_REGEX.find(parseableText(frame))
        return match?.groupValues?.get(1)
    }

    /**
     * Extracts the method name from a stack frame's rendered presentation.
     */
    fun extractMethodName(frame: XStackFrame): String? {
        val match = METHOD_NAME_REGEX.find(parseableText(frame))
        return match?.groupValues?.get(1)
    }

    /**
     * Human-readable one-line summary of a frame, derived from the same source position the
     * machine-readable fields use.
     *
     * The platform's `XStackFrame.toString()` encodes a 0-based line number, which reads as
     * off-by-one next to the 1-based `line` field it shipped alongside (live-QA finding 4.1) —
     * so it is used only as the fallback for frames with no source position, where there is no
     * line to disagree with.
     */
    fun formatPresentation(frame: XStackFrame): String {
        val position = frame.sourcePosition ?: return renderLabel(frame) ?: frame.toString()
        val location = "${position.file.name}:${position.line + 1}"
        val className = extractClassName(frame)?.substringAfterLast('.')
        val methodName = extractMethodName(frame)
        if (className != null && methodName != null) {
            return "$className.$methodName($location)"
        }
        // Languages whose frames do not render in `Class.method(...)` shape still have a
        // useful label; falling straight through to `file:line` threw away the function name.
        return combineLabelAndLocation(renderLabel(frame), location)
    }

    /**
     * Joins a rendered label with the frame's location without repeating the location.
     *
     * Most non-JVM labels already carry it - Delve renders "pkg.Func (file.go:12) module" -
     * so appending it unconditionally produced "... module (file.go:12)", which reads like
     * two frames run together.
     */
    internal fun combineLabelAndLocation(label: String?, location: String): String = when {
        label.isNullOrBlank() -> location
        label.contains(location) -> label
        else -> "$label ($location)"
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

                // Frames arrive in batches (last=false ... last=true), so the resume condition
                // can hold for more than one batch; resuming twice throws IllegalStateException
                // on the debugger's own thread. Same pattern as EvaluatorUtils.
                val resumed = AtomicBoolean(false)
                continuation.invokeOnCancellation { resumed.set(true) }

                executionStack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        frames.addAll(stackFrames)
                        if ((last || frames.size > frameIndex) && resumed.compareAndSet(false, true)) {
                            continuation.resume(frames.getOrNull(frameIndex))
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        if (resumed.compareAndSet(false, true)) {
                            continuation.resume(frames.getOrNull(frameIndex))
                        }
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

                // Frames arrive in batches (last=false ... last=true), so the resume condition
                // can hold for more than one batch; resuming twice throws IllegalStateException
                // on the debugger's own thread. Same pattern as EvaluatorUtils.
                val resumed = AtomicBoolean(false)
                continuation.invokeOnCancellation { resumed.set(true) }

                executionStack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        collectedFrames.addAll(stackFrames)
                        if ((last || collectedFrames.size >= limit - 1) && resumed.compareAndSet(false, true)) {
                            continuation.resume(collectedFrames.take(limit - 1))
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        if (resumed.compareAndSet(false, true)) {
                            continuation.resume(collectedFrames.toList())
                        }
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
