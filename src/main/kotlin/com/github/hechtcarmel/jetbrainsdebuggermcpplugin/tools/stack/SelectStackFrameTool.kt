package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SelectFrameResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackFrameInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XStackFrame
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SelectStackFrameTool : AbstractMcpTool() {

    override val name = "select_stack_frame"

    override val description = """
        Changes the current stack frame context for variable inspection and expression evaluation.
        Use to inspect variables in a caller's scope. Frame 0 is the current function; higher indices are callers.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.idempotentMutable("Select Stack Frame")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("frame_index", integerProperty("Index of the stack frame to select (0 = topmost)", minimum = 0))
        }
        putJsonArray("required") {
            add(JsonPrimitive("frame_index"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val frameIndex = ToolArguments.requireInt(arguments, "frame_index", min = 0)

        val session = requirePausedSession(project, sessionId, "select stack frame")

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val executionStack = suspendContext.activeExecutionStack
            ?: return createErrorResult("No execution stack available")

        val frames = StackFrameUtils.collectStackFrames(executionStack, frameIndex + 1)
        if (frameIndex >= frames.size) {
            return createErrorResult("Frame index $frameIndex out of bounds (max: ${frames.size - 1})")
        }

        val targetFrame = frames[frameIndex]
        onEdt { session.setCurrentStackFrame(executionStack, targetFrame) }

        val frameInfo = createFrameInfo(targetFrame, frameIndex)

        return createJsonResult(SelectFrameResult(
            sessionId = getSessionId(session),
            frameIndex = frameIndex,
            frame = frameInfo,
            message = "Selected frame $frameIndex"
        ))
    }

    private fun createFrameInfo(frame: XStackFrame, index: Int): StackFrameInfo {
        val position = frame.sourcePosition
        val path = position?.file?.path

        return StackFrameInfo(
            index = index,
            file = path,
            line = position?.let { it.line + 1 },
            className = StackFrameUtils.extractClassName(frame),
            methodName = StackFrameUtils.extractMethodName(frame),
            isCurrent = true,
            isLibrary = StackFrameUtils.isLibraryPath(path),
            presentation = frame.toString().take(100)
        )
    }
}
