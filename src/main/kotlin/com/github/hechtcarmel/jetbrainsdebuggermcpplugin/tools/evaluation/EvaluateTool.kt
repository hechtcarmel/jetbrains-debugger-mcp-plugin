package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluateResponse
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluationResult
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.Icon
import kotlin.coroutines.resume

/**
 * Evaluates an expression in the current debug context.
 */
class EvaluateTool : AbstractMcpTool() {

    override val name = "evaluate"

    override val description = """
        Evaluates an expression in the context of the current stack frame.
        Returns the result value, type, and whether it has children (expandable).
        Use to inspect values, call methods, or modify state during debugging.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("expression") {
                put("type", "string")
                put("description", "Expression to evaluate (e.g., variable name, method call, arithmetic)")
            }
            putJsonObject("frame_index") {
                put("type", "integer")
                put("description", "Stack frame index for evaluation context. Default: 0 (current frame)")
                put("minimum", 0)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("expression"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val expression = arguments["expression"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: expression")
        val frameIndex = arguments["frame_index"]?.jsonPrimitive?.intOrNull ?: 0

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to evaluate expressions")
        }

        val currentFrame = session.currentStackFrame
            ?: return createErrorResult("No current stack frame")

        // For now, we only support frame_index 0
        if (frameIndex != 0) {
            return createErrorResult("Currently only frame_index 0 is supported")
        }

        val evaluator = currentFrame.evaluator
            ?: return createErrorResult("No evaluator available for current frame")

        val result = evaluateExpression(evaluator, expression)
            ?: return createErrorResult("Evaluation timed out or failed")

        return createJsonResult(EvaluateResponse(
            sessionId = getSessionId(session),
            frameIndex = frameIndex,
            result = result
        ))
    }

    private suspend fun evaluateExpression(
        evaluator: XDebuggerEvaluator,
        expression: String
    ): EvaluationResult? {
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { continuation ->
                val xExpression = XExpressionImpl.fromText(expression)

                evaluator.evaluate(
                    xExpression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(result: XValue) {
                            // Get the presentation of the result
                            getValuePresentation(result, expression) { evalResult ->
                                continuation.resume(evalResult)
                            }
                        }

                        override fun errorOccurred(errorMessage: String) {
                            continuation.resume(EvaluationResult(
                                expression = expression,
                                value = "",
                                type = "error",
                                hasChildren = false,
                                error = errorMessage
                            ))
                        }
                    },
                    null
                )
            }
        }
    }

    private fun getValuePresentation(
        value: XValue,
        expression: String,
        callback: (EvaluationResult) -> Unit
    ) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(EvaluationResult(
                    expression = expression,
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
                        override fun renderValue(value: String) { append(value) }
                        override fun renderStringValue(value: String) { append("\"$value\"") }
                        override fun renderNumericValue(value: String) { append(value) }
                        override fun renderKeywordValue(value: String) { append(value) }
                        override fun renderValue(
                            value: String,
                            key: com.intellij.openapi.editor.colors.TextAttributesKey
                        ) { append(value) }
                        override fun renderStringValue(
                            value: String,
                            additionalSpecialCharsToHighlight: String?,
                            maxLength: Int
                        ) { append("\"$value\"") }
                        override fun renderComment(comment: String) { append(" // $comment") }
                        override fun renderSpecialSymbol(symbol: String) { append(symbol) }
                        override fun renderError(error: String) { append("ERROR: $error") }
                    })
                }

                callback(EvaluationResult(
                    expression = expression,
                    value = valueText,
                    type = presentation.type ?: "unknown",
                    hasChildren = hasChildren
                ))
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

            override fun isObsolete(): Boolean = false
        }, com.intellij.xdebugger.frame.XValuePlace.TREE)
    }
}
