package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SelectFrameResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackFrameInfo
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

class SelectStackFrameTool : AbstractMcpTool() {

    override val name = "select_stack_frame"

    override val description = """
        Selects a specific stack frame as the current context for variable inspection and evaluation.
        Use get_stack_trace first to see available frames and their indices.
        Frame index 0 is the current (topmost) frame.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("frame_index") {
                put("type", "integer")
                put("description", "Index of the stack frame to select (0 = topmost)")
                put("minimum", 0)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("frame_index"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val frameIndex = arguments["frame_index"]?.jsonPrimitive?.intOrNull
            ?: return createErrorResult("Missing required parameter: frame_index")

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to select stack frame")
        }

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val executionStack = suspendContext.activeExecutionStack
            ?: return createErrorResult("No execution stack available")

        val frames = getStackFrames(executionStack, frameIndex + 1)
        if (frameIndex >= frames.size) {
            return createErrorResult("Frame index $frameIndex out of bounds (max: ${frames.size - 1})")
        }

        val targetFrame = frames[frameIndex]
        session.setCurrentStackFrame(executionStack, targetFrame)

        val position = targetFrame.sourcePosition
        val frameInfo = StackFrameInfo(
            index = frameIndex,
            file = position?.file?.path,
            line = position?.let { it.line + 1 },
            className = extractClassName(targetFrame),
            methodName = extractMethodName(targetFrame),
            isCurrent = true,
            isLibrary = position?.file?.path?.contains(".jar!") == true,
            presentation = targetFrame.toString().take(100)
        )

        return createJsonResult(SelectFrameResult(
            sessionId = getSessionId(session),
            frameIndex = frameIndex,
            frame = frameInfo,
            message = "Selected frame $frameIndex"
        ))
    }

    private suspend fun getStackFrames(executionStack: XExecutionStack, limit: Int): List<XStackFrame> {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                val frames = mutableListOf<XStackFrame>()

                executionStack.topFrame?.let { frames.add(it) }

                executionStack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        frames.addAll(stackFrames)
                        if (last || frames.size >= limit) {
                            continuation.resume(frames.take(limit))
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        continuation.resume(frames)
                    }
                })
            }
        } ?: emptyList()
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
}
