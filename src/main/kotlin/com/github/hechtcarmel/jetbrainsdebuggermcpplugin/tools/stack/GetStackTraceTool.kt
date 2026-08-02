package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackFrameInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackTraceResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XStackFrame
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gets the stack trace for the current debug session.
 */
class GetStackTraceTool : AbstractMcpTool() {

    override val name = "get_stack_trace"

    override val description = """
        Returns the call stack showing how execution reached the current point.
        Use to understand the sequence of function calls. Each frame includes file, line, class, and method information.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Get Stack Trace")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string"); put("description", "Debug session ID") }
            putJsonObject("threadId") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Thread ID or name") }
            putJsonObject("frames") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("index") { put("type", "integer"); put("description", "Frame index (0 = current/topmost)") }
                        putJsonObject("file") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Absolute path to source file") }
                        putJsonObject("line") { putJsonArray("type") { add(JsonPrimitive("integer")); add(JsonPrimitive("null")) }; put("description", "Line number (1-based)") }
                        putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Fully qualified class name") }
                        putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Method or function name") }
                        putJsonObject("isCurrent") { put("type", "boolean"); put("description", "True if this is the current frame") }
                        putJsonObject("isLibrary") { put("type", "boolean"); put("description", "True if this frame is in library code") }
                    }
                }
                put("description", "Stack frames from current (0) to oldest")
            }
            putJsonObject("totalFrames") { put("type", "integer"); put("description", "Total number of frames returned") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")); add(JsonPrimitive("frames")); add(JsonPrimitive("totalFrames")) })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("max_frames", integerProperty("Maximum number of frames to return", default = 50, minimum = 1, maximum = 200))
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val maxFrames = ToolArguments.optionalInt(arguments, "max_frames", default = 50, min = 1, max = 200)

        val session = requirePausedSession(project, sessionId, "get stack trace")

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val executionStack = suspendContext.activeExecutionStack
            ?: return createErrorResult("No execution stack available")

        val xFrames = StackFrameUtils.collectStackFrames(executionStack, maxFrames)
        val frames = xFrames.mapIndexed { index, frame -> createFrameInfo(frame, index) }

        return createJsonResult(StackTraceResult(
            sessionId = getSessionId(session),
            threadId = executionStack.displayName,
            frames = frames,
            totalFrames = frames.size
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
            isCurrent = index == 0,
            isLibrary = StackFrameUtils.isLibraryPath(path),
            presentation = frame.toString().take(150)
        )
    }
}
