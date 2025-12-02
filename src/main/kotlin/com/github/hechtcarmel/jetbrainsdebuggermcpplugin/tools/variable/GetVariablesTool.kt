package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariablesResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VariablePresentationUtils
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.swing.Icon
import kotlin.coroutines.resume

/**
 * Gets variables from the current stack frame.
 */
class GetVariablesTool : AbstractMcpTool() {

    override val name = "get_variables"

    override val description = """
        Returns all variables visible in the current (or specified) stack frame.
        Use to inspect local variables, parameters, and accessible fields. For complex objects, use evaluate_expression to see their contents.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("Get Variables")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string"); put("description", "Debug session ID") }
            putJsonObject("frameIndex") { put("type", "integer"); put("description", "Stack frame index that variables were retrieved from") }
            putJsonObject("variables") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string"); put("description", "Variable name") }
                        putJsonObject("value") { put("type", "string"); put("description", "String representation of the value") }
                        putJsonObject("type") { put("type", "string"); put("description", "Variable type name") }
                        putJsonObject("hasChildren") { put("type", "boolean"); put("description", "True if this variable has child properties") }
                    }
                    put("required", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("value")); add(JsonPrimitive("type")); add(JsonPrimitive("hasChildren")) })
                }
                put("description", "List of variables visible in the stack frame")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")); add(JsonPrimitive("frameIndex")); add(JsonPrimitive("variables")) })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("frame_index") {
                put("type", "integer")
                put("description", "Stack frame index (0 = current frame)")
                put("default", 0)
                put("minimum", 0)
            }
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val frameIndex = arguments["frame_index"]?.jsonPrimitive?.intOrNull ?: 0

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to get variables")
        }

        val frame = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            StackFrameUtils.getFrameAtIndex(session, frameIndex)
        } ?: return createErrorResult("No stack frame available at index $frameIndex")

        val variables = getVariablesFromFrame(frame)

        return createJsonResult(VariablesResult(
            sessionId = getSessionId(session),
            frameIndex = frameIndex,
            variables = variables
        ))
    }

    private suspend fun getVariablesFromFrame(frame: XStackFrame): List<VariableInfo> {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                val variables = mutableListOf<VariableInfo>()
                var completed = false
                var pendingPresentations = 0

                frame.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        pendingPresentations += children.size()

                        for (i in 0 until children.size()) {
                            val name = children.getName(i)
                            val value = children.getValue(i)

                            VariablePresentationUtils.computeValuePresentation(name, value) { varInfo ->
                                synchronized(variables) {
                                    variables.add(varInfo)
                                    pendingPresentations--

                                    if (last && pendingPresentations <= 0 && !completed) {
                                        completed = true
                                        continuation.resume(variables.toList())
                                    }
                                }
                            }
                        }

                        if (last && pendingPresentations <= 0 && !completed) {
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

                    override fun setErrorMessage(
                        errorMessage: String,
                        link: XDebuggerTreeNodeHyperlink?
                    ) {
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

                    @Deprecated("Deprecated in Java")
                    override fun tooManyChildren(remaining: Int) {}

                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}

                    override fun isObsolete(): Boolean = false
                })
            }
        } ?: emptyList()
    }
}
