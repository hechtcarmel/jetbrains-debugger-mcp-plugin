package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariablesResult
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
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
import kotlinx.serialization.json.JsonObject
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
        Gets all variables visible in the current stack frame.
        Returns variable names, values, types, and whether they have children (expandable).
        Use expand_variable to see children of complex objects.
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
                put("description", "Stack frame index (0 = current frame). Default: 0")
                put("minimum", 0)
            }
        }
        put("required", buildJsonArray { })
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

        val currentFrame = session.currentStackFrame
            ?: return createErrorResult("No current stack frame")

        if (frameIndex != 0) {
            return createErrorResult("Currently only frame_index 0 is supported")
        }

        val variables = getVariablesFromFrame(currentFrame)

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

                            computeValuePresentation(name, value) { varInfo ->
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

                    override fun tooManyChildren(remaining: Int) {}

                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}

                    override fun isObsolete(): Boolean = false
                })
            }
        } ?: emptyList()
    }

    private fun computeValuePresentation(
        name: String,
        value: XValue,
        callback: (VariableInfo) -> Unit
    ) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(VariableInfo(
                    name = name,
                    value = valueText,
                    type = type ?: "unknown",
                    hasChildren = hasChildren
                ))
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
                        override fun renderValue(
                            v: String,
                            key: com.intellij.openapi.editor.colors.TextAttributesKey
                        ) { append(v) }
                        override fun renderStringValue(
                            v: String,
                            additionalSpecialCharsToHighlight: String?,
                            maxLength: Int
                        ) { append("\"$v\"") }
                        override fun renderComment(comment: String) { append(" // $comment") }
                        override fun renderSpecialSymbol(symbol: String) { append(symbol) }
                        override fun renderError(error: String) { append("ERROR: $error") }
                    })
                }

                callback(VariableInfo(
                    name = name,
                    value = valueText,
                    type = presentation.type ?: "unknown",
                    hasChildren = hasChildren
                ))
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
    }
}
