package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.Icon
import kotlin.coroutines.resume

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

        return DebugSessionStatus(
            sessionId = session.hashCode().toString(),
            name = session.sessionName,
            state = when {
                session.isStopped -> "stopped"
                isPaused -> "paused"
                else -> "running"
            },
            pausedReason = if (isPaused) determinePauseReason(session) else null,
            currentLocation = currentFrame?.let { getSourceLocation(it) },
            breakpointHit = if (isPaused) getBreakpointHitInfo(session) else null,
            stackSummary = if (isPaused) getStackSummary(session, maxStackFrames) else emptyList(),
            totalStackDepth = if (isPaused) getStackDepth(session) else 0,
            variables = if (isPaused && includeVariables) getVariables(currentFrame) else emptyList(),
            sourceContext = if (isPaused && includeSourceContext)
                getSourceContext(project, currentFrame, sourceContextLines) else null,
            currentThread = getCurrentThreadInfo(session),
            threadCount = 1
        )
    }

    fun determinePauseReason(session: XDebugSession): String {
        val position = session.currentStackFrame?.sourcePosition ?: return "step"
        val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager

        val breakpoint = breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().find { bp ->
            bp.fileUrl == position.file.url && bp.line == position.line
        }

        return if (breakpoint != null) "breakpoint" else "step"
    }

    fun getBreakpointHitInfo(session: XDebugSession): BreakpointHitInfo? {
        val position = session.currentStackFrame?.sourcePosition ?: return null
        val breakpointManager = XDebuggerManager.getInstance(session.project).breakpointManager

        val breakpoint = breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().find { bp ->
            bp.fileUrl == position.file.url && bp.line == position.line
        } ?: return null

        return BreakpointHitInfo(
            breakpointId = breakpoint.hashCode().toString(),
            type = "line",
            file = position.file.path,
            line = position.line + 1,
            condition = breakpoint.conditionExpression?.expression,
            hitCount = 0
        )
    }

    suspend fun getVariables(frame: XStackFrame?): List<VariableInfo> {
        if (frame == null) return emptyList()

        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { continuation ->
                val variables = mutableListOf<VariableInfo>()
                var completed = false

                frame.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            val name = children.getName(i)
                            val value = children.getValue(i)

                            getValuePresentation(value) { presentation, type, hasChildren ->
                                synchronized(variables) {
                                    variables.add(VariableInfo(
                                        name = name,
                                        value = presentation,
                                        type = type,
                                        hasChildren = hasChildren
                                    ))
                                }
                            }
                        }

                        if (last && !completed) {
                            completed = true
                            continuation.resume(variables.toList())
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}

                    override fun setErrorMessage(errorMessage: String) {
                        if (!completed) {
                            completed = true
                            continuation.resume(emptyList())
                        }
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        if (!completed) {
                            completed = true
                            continuation.resume(emptyList())
                        }
                    }

                    override fun setMessage(
                        message: String,
                        icon: Icon?,
                        attributes: SimpleTextAttributes,
                        link: XDebuggerTreeNodeHyperlink?
                    ) {}

                    override fun tooManyChildren(remaining: Int) {}

                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}

                    override fun isObsolete(): Boolean = false
                })
            }
        } ?: emptyList()
    }

    private fun getSourceLocation(frame: XStackFrame): SourceLocation? {
        val position = frame.sourcePosition ?: return null
        return SourceLocation(
            file = position.file.path,
            line = position.line + 1,
            className = extractClassName(frame),
            methodName = extractMethodName(frame),
            signature = null
        )
    }

    private fun extractClassName(frame: XStackFrame): String? {
        val presentation = frame.toString()
        val match = Regex("""([a-zA-Z_][\w.]*)\.[a-zA-Z_]\w*\(""").find(presentation)
        return match?.groupValues?.get(1)
    }

    private fun extractMethodName(frame: XStackFrame): String? {
        val presentation = frame.toString()
        val match = Regex("""\.([a-zA-Z_]\w*)\(""").find(presentation)
        return match?.groupValues?.get(1)
    }

    private fun getStackSummary(session: XDebugSession, maxFrames: Int): List<StackFrameInfo> {
        val frames = mutableListOf<StackFrameInfo>()
        val frame = session.currentStackFrame

        if (frame != null) {
            val position = frame.sourcePosition
            frames.add(StackFrameInfo(
                index = 0,
                file = position?.file?.path,
                line = position?.let { it.line + 1 },
                className = extractClassName(frame),
                methodName = extractMethodName(frame),
                isCurrent = true,
                isLibrary = position?.file?.path?.contains(".jar!") == true,
                presentation = frame.toString().take(100)
            ))
        }

        return frames
    }

    private fun getStackDepth(session: XDebugSession): Int {
        return if (session.currentStackFrame != null) 1 else 0
    }

    private fun getValuePresentation(
        value: XValue,
        callback: (String, String, Boolean) -> Unit
    ) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(valueText, type ?: "unknown", hasChildren)
            }

            override fun setPresentation(
                icon: Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean
            ) {
                val valueText = buildString {
                    presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(v: String) { append(v) }
                        override fun renderStringValue(v: String) { append("\"$v\"") }
                        override fun renderNumericValue(v: String) { append(v) }
                        override fun renderKeywordValue(v: String) { append(v) }
                        override fun renderValue(v: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { append(v) }
                        override fun renderStringValue(v: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { append("\"$v\"") }
                        override fun renderComment(comment: String) { append(" // $comment") }
                        override fun renderSpecialSymbol(symbol: String) { append(symbol) }
                        override fun renderError(error: String) { append("ERROR: $error") }
                    })
                }
                callback(valueText, presentation.type ?: "unknown", hasChildren)
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
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

    private fun getCurrentThreadInfo(session: XDebugSession): ThreadInfo? {
        session.suspendContext ?: return null
        return ThreadInfo(
            id = "main",
            name = "main",
            state = if (session.isPaused) "paused" else "running",
            isCurrent = true
        )
    }
}
