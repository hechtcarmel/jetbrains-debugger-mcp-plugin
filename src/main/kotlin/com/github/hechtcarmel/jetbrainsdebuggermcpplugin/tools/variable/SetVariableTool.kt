package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetVariableResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VariablePresentationUtils
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        Changes the value of a variable during debugging.
        Use to test different values or fix incorrect state. Supports primitives, strings, and simple expressions. This modifies the running program's state.

        **Language limitations:** Native debuggers (LLDB/GDB) used for Rust, C++, and Go have limited support for modifying complex types like strings, collections, or heap-allocated values. Works best in languages with full debug support (Java, Kotlin, Python, JavaScript).
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Set Variable", destructive = true)

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
                put("description", "New value as a string expression. For primitives: '42', '3.14', 'true'. For strings: '\"hello\"' (with quotes). For null: 'null'. Can also be an expression that evaluates to the target type.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("variable_name"))
            add(JsonPrimitive("new_value"))
        }
        put("additionalProperties", false)
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

        val (_, oldValue, type) = findVariableByName(currentFrame, variableName)
            ?: return createErrorResult("Variable not found: $variableName")

        val evaluator = currentFrame.evaluator
            ?: return createErrorResult("No evaluator available - cannot modify variable")

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
                                VariablePresentationUtils.computeSimplePresentation(foundVariable!!) { displayValue, type ->
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

    private suspend fun evaluateAssignment(
        evaluator: XDebuggerEvaluator,
        assignmentExpression: String
    ): SetResult {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                val xExpression = XDebuggerUtil.getInstance().createExpression(assignmentExpression, null, null, EvaluationMode.EXPRESSION)

                evaluator.evaluate(
                    xExpression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(result: XValue) {
                            VariablePresentationUtils.computeSimplePresentation(result) { valueText, _ ->
                                continuation.resume(SetResult(success = true, resultValue = valueText))
                            }
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
