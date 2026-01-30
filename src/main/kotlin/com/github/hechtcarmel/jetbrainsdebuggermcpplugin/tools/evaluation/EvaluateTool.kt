package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluateResponse
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluationResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VariablePresentationUtils
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
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

/**
 * Evaluates an expression in the current debug context.
 */
class EvaluateTool : AbstractMcpTool() {

    override val name = "evaluate_expression"

    override val description = """
        Evaluates an arbitrary expression in the current debug context and returns the result.
        Use to compute values, call methods, or inspect complex expressions. Can modify state if the expression has side effects.

        **Language limitations:** Native debuggers (LLDB/GDB) used for Rust, C++, and Go have limited expression evaluation. Method calls (e.g., `s.len()`, `vec.size()`) may not work. Variable inspection works well. Full expression support is available in Java, Kotlin, Python, and JavaScript.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Evaluate Expression")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("expression") {
                put("type", "string")
                put("description", "Expression to evaluate in the current context. Can be a variable name, method call, arithmetic, or complex expression. Examples: 'x', 'list.size()', 'a + b * 2', 'String.format(\"%d\", count)'")
            }
            putJsonObject("frame_index") {
                put("type", "integer")
                put("description", "Stack frame index for evaluation context (0 = current frame)")
                put("default", 0)
                put("minimum", 0)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("expression"))
        }
        put("additionalProperties", false)
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

        val frame = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            StackFrameUtils.getFrameAtIndex(session, frameIndex)
        } ?: return createErrorResult("No stack frame available at index $frameIndex")

        val evaluator = frame.evaluator
            ?: return createErrorResult("No evaluator available for frame at index $frameIndex")

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
                val xExpression = XDebuggerUtil.getInstance().createExpression(expression, null, null, EvaluationMode.EXPRESSION)

                evaluator.evaluate(
                    xExpression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(result: XValue) {
                            VariablePresentationUtils.computeFullPresentation(result) { presentation ->
                                continuation.resume(EvaluationResult(
                                    expression = expression,
                                    value = presentation.value,
                                    type = presentation.type,
                                    hasChildren = presentation.hasChildren
                                ))
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
}
