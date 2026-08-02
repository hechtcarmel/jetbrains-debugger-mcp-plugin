package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.frame.XStackFrame

object SessionStatusCollector {

    suspend fun collectStatus(
        project: Project,
        session: XDebugSession,
        includeVariables: Boolean = true,
        includeSourceContext: Boolean = true,
        sourceContextLines: Int = 5,
        maxStackFrames: Int = 10
    ): DebugSessionStatus {
        val currentFrame = session.currentStackFrame
        val isPaused = session.isPaused

        val suspendContext = if (isPaused) session.suspendContext else null
        val executionStack = suspendContext?.activeExecutionStack
        val stackFrames = if (executionStack != null) {
            StackFrameUtils.collectStackFrames(executionStack, maxStackFrames)
        } else {
            emptyList()
        }
        val threads = if (suspendContext != null) {
            ExecutionStackUtils.collectExecutionStacks(suspendContext)
        } else {
            emptyList()
        }

        return DebugSessionStatus(
            sessionId = StableObjectIds.idFor(session),
            name = session.sessionName,
            state = when {
                session.isStopped -> "stopped"
                isPaused -> "paused"
                else -> "running"
            },
            pausedReason = if (isPaused) determinePauseReason(session) else null,
            currentLocation = if (isPaused) currentFrame?.let { getSourceLocation(it) } else null,
            breakpointHit = if (isPaused) getBreakpointHitInfo(session) else null,
            stackSummary = buildStackSummary(stackFrames, currentFrame),
            totalStackDepth = stackFrames.size,
            variables = if (isPaused && includeVariables) getVariables(currentFrame) else emptyList(),
            sourceContext = if (isPaused && includeSourceContext)
                getSourceContext(project, currentFrame, sourceContextLines) else null,
            currentThread = executionStack?.let { stack ->
                ThreadInfo(
                    id = stack.hashCode().toString(),
                    name = stack.displayName,
                    state = if (isPaused) "paused" else "running",
                    isCurrent = true
                )
            },
            threadCount = threads.size
        )
    }

    fun determinePauseReason(session: XDebugSession): String {
        val position = session.topFramePosition ?: return "step"
        return if (findEnabledBreakpointAt(session, position) != null) "breakpoint" else "step"
    }

    fun getBreakpointHitInfo(session: XDebugSession): BreakpointHitInfo? {
        val position = session.topFramePosition ?: return null
        val breakpoint = findEnabledBreakpointAt(session, position) ?: return null

        return BreakpointHitInfo(
            breakpointId = StableObjectIds.idFor(breakpoint),
            type = "line",
            file = position.file.path,
            line = position.line + 1,
            condition = breakpoint.conditionExpression?.expression,
            hitCount = 0
        )
    }

    /**
     * The breakpoint that can explain the current pause. The pause site is the *top* frame's
     * position — select_stack_frame changes currentStackFrame, and the answer must not change
     * with it. Muted or disabled breakpoints cannot have fired, so they never match — and neither
     * can a `suspend_policy: none` tracepoint, which logs without ever suspending; before this
     * filter, stepping onto a tracepoint's line was misattributed to the tracepoint
     * (live-QA finding 4.2).
     */
    private fun findEnabledBreakpointAt(session: XDebugSession, position: XSourcePosition): XLineBreakpoint<*>? {
        if (session.areBreakpointsMuted()) return null
        val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager
        return XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<XLineBreakpointType<*>>()
            .flatMap { type ->
                @Suppress("UNCHECKED_CAST")
                breakpointManager.findBreakpointsAtLine(
                    type as XLineBreakpointType<XBreakpointProperties<*>>,
                    position.file,
                    position.line
                )
            }
            .firstOrNull { it.isEnabled && it.suspendPolicy != SuspendPolicy.NONE }
    }

    suspend fun getVariables(frame: XStackFrame?): List<VariableInfo> {
        if (frame == null) return emptyList()
        return FrameVariablesCollector.collectVariables(frame)
    }

    private fun getSourceLocation(frame: XStackFrame): SourceLocation? {
        val position = frame.sourcePosition ?: return null
        return SourceLocation(
            file = position.file.path,
            line = position.line + 1,
            className = StackFrameUtils.extractClassName(frame),
            methodName = StackFrameUtils.extractMethodName(frame),
            signature = null
        )
    }

    private fun buildStackSummary(frames: List<XStackFrame>, currentFrame: XStackFrame?): List<StackFrameInfo> {
        return frames.mapIndexed { index, frame ->
            val position = frame.sourcePosition
            val path = position?.file?.path
            StackFrameInfo(
                index = index,
                file = path,
                line = position?.let { it.line + 1 },
                className = StackFrameUtils.extractClassName(frame),
                methodName = StackFrameUtils.extractMethodName(frame),
                isCurrent = if (currentFrame != null) frame == currentFrame else index == 0,
                isLibrary = StackFrameUtils.isLibraryPath(path),
                presentation = StackFrameUtils.formatPresentation(frame).take(100)
            )
        }
    }

    private fun getSourceContext(
        project: Project,
        frame: XStackFrame?,
        contextLines: Int
    ): SourceContext? {
        val position = frame?.sourcePosition ?: return null
        val file = position.file
        val currentLine = position.line + 1

        val (startLine, endLine, lines) = ReadAction.compute<Triple<Int, Int, List<SourceLine>>?, Throwable> {
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: return@compute null

            val start = maxOf(1, currentLine - contextLines)
            val end = minOf(document.lineCount, currentLine + contextLines)

            val sourceLines = (start..end).mapNotNull { lineNum ->
                try {
                    val lineIndex = lineNum - 1
                    if (lineIndex >= 0 && lineIndex < document.lineCount) {
                        val lineStart = document.getLineStartOffset(lineIndex)
                        val lineEnd = document.getLineEndOffset(lineIndex)
                        val content = document.getText(TextRange(lineStart, lineEnd))
                        SourceLine(
                            number = lineNum,
                            content = content,
                            isCurrent = lineNum == currentLine
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            Triple(start, end, sourceLines)
        } ?: return null

        val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
        val breakpointsInView = breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { bp ->
                bp.fileUrl == file.url &&
                bp.line + 1 in startLine..endLine
            }
            .map { it.line + 1 }

        return SourceContext(
            file = file.path,
            startLine = startLine,
            endLine = endLine,
            currentLine = currentLine,
            lines = lines,
            breakpointsInView = breakpointsInView
        )
    }
}
