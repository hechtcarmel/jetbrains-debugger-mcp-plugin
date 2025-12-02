package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetVariableResult
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.Icon
import kotlin.coroutines.resume

class SetVariableTool : AbstractMcpTool() {

    override val name = "set_variable"

    override val description = """
        Modifies the value of a variable in the current debug context.
        Can set primitive values, strings, and some object references.
        Use get_variables first to identify available variables.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("variable_name") {
                put("type", "string")
                put("description", "Name of the variable to modify")
            }
            putJsonObject("new_value") {
                put("type", "string")
                put("description", "New value as a string expression (e.g., '42', '\"hello\"', 'true')")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("variable_name"))
            add(JsonPrimitive("new_value"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val variableName = arguments["variable_name"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: variable_name")
        val newValue = arguments["new_value"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: new_value")

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to modify variables")
        }

        val currentFrame = session.currentStackFrame
            ?: return createErrorResult("No current stack frame")

        // First, get the current value and type of the variable
        val (_, oldValue, type) = findVariableByName(currentFrame, variableName)
            ?: return createErrorResult("Variable not found: $variableName")

        // Get the evaluator to execute the assignment
        val evaluator = currentFrame.evaluator
            ?: return createErrorResult("No evaluator available - cannot modify variable")

        // Use assignment expression to set the variable value
        val assignmentExpression = "$variableName = $newValue"
        val setResult = evaluateAssignment(evaluator, assignmentExpression)

        return if (setResult.success) {
            createJsonResult(SetVariableResult(
                sessionId = getSessionId(session),
                variableName = variableName,
                oldValue = oldValue,
                newValue = setResult.resultValue ?: newValue,
                type = type,
                message = "Variable '$variableName' set to ${setResult.resultValue ?: newValue}"
            ))
        } else {
            createErrorResult("Failed to set variable: ${setResult.error ?: "unknown error"}")
        }
    }

    private data class VariableData(
        val value: XValue,
        val displayValue: String,
        val type: String
    )

    private data class SetResult(
        val success: Boolean,
        val resultValue: String? = null,
        val error: String? = null
    )

    private suspend fun findVariableByName(frame: XStackFrame, targetName: String): VariableData? {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                var result: VariableData? = null
                var pendingPresentations = 0
                var foundVariable: XValue? = null
                var completed = false

                frame.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            val name = children.getName(i)
                            if (name == targetName) {
                                foundVariable = children.getValue(i)
                                pendingPresentations++
                                computePresentation(foundVariable!!) { displayValue, type ->
                                    synchronized(this@SetVariableTool) {
                                        result = VariableData(foundVariable!!, displayValue, type)
                                        pendingPresentations--
                                        if (!completed) {
                                            completed = true
                                            continuation.resume(result)
                                        }
                                    }
                                }
                                break
                            }
                        }

                        if (last && foundVariable == null && !completed) {
                            completed = true
                            continuation.resume(null)
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) {
                        if (!completed) {
                            completed = true
                            continuation.resume(null)
                        }
                    }
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        if (!completed) {
                            completed = true
                            continuation.resume(null)
                        }
                    }
                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                    override fun tooManyChildren(remaining: Int) {}
                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}
                    override fun isObsolete(): Boolean = false
                })
            }
        }
    }

    private fun computePresentation(value: XValue, callback: (String, String) -> Unit) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(valueText, type ?: "unknown")
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
                callback(valueText, presentation.type ?: "unknown")
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
    }

    private suspend fun evaluateAssignment(
        evaluator: XDebuggerEvaluator,
        assignmentExpression: String
    ): SetResult {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                val xExpression = XExpressionImpl.fromText(assignmentExpression)

                evaluator.evaluate(
                    xExpression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(result: XValue) {
                            // Get the result value from the assignment
                            result.computePresentation(object : XValueNode {
                                override fun setPresentation(
                                    icon: Icon?,
                                    type: String?,
                                    valueText: String,
                                    hasChildren: Boolean
                                ) {
                                    continuation.resume(SetResult(success = true, resultValue = valueText))
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
                                            override fun renderComment(comment: String) {}
                                            override fun renderSpecialSymbol(symbol: String) { append(symbol) }
                                            override fun renderError(error: String) { append("ERROR: $error") }
                                        })
                                    }
                                    continuation.resume(SetResult(success = true, resultValue = valueText))
                                }

                                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                                override fun isObsolete(): Boolean = false
                            }, XValuePlace.TREE)
                        }

                        override fun errorOccurred(errorMessage: String) {
                            continuation.resume(SetResult(success = false, error = errorMessage))
                        }
                    },
                    null
                )
            }
        } ?: SetResult(success = false, error = "Timeout while setting variable value")
    }
}
