package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluateResponse
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluationResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.EvaluatorUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VariablePresentationUtils
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Evaluates an expression in the current debug context.
 */
class EvaluateTool : AbstractMcpTool() {

    override val name = "evaluate_expression"

    override val description = """
        Evaluates an arbitrary expression in the current debug context and returns the result.
        Use to compute values, call methods, or inspect complex expressions. Can modify state if the expression has side effects.
        Evaluation is filtered by the IDE's Evaluate Expression safety mode unless it is set to Unrestricted.

        **Language limitations:** Native debuggers (LLDB/GDB) used for Rust, C++, and Go have limited expression evaluation. Method calls (e.g., `s.len()`, `vec.size()`) may not work. Variable inspection works well. Full expression support is available in Java, Kotlin, Python, and JavaScript.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Evaluate Expression")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("expression", stringProperty("Expression to evaluate in the current context. Can be a variable name, method call, arithmetic, or complex expression. May be blocked by the IDE's Evaluate Expression safety mode. Examples: 'x', 'list.size()', 'a + b * 2', 'String.format(\"%d\", count)'"))
            put("frame_index", integerProperty("Stack frame index for evaluation context (0 = current frame)", default = 0, minimum = 0))
        }
        putJsonArray("required") {
            add(JsonPrimitive("expression"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val expression = ToolArguments.requireString(arguments, "expression")
        val frameIndex = ToolArguments.optionalInt(arguments, "frame_index", default = 0, min = 0)

        val session = requirePausedSession(project, sessionId, "evaluate expressions")

        val frame = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            StackFrameUtils.getFrameAtIndex(session, frameIndex)
        } ?: return createErrorResult("No stack frame available at index $frameIndex")

        val evaluator = frame.evaluator
            ?: return createErrorResult("No evaluator available for frame at index $frameIndex")

        val settings = McpSettings.getInstance()
        val safetyViolation = EvaluateExpressionSafetyGuard.validate(
            expression = expression,
            mode = settings.evaluateExpressionSafetyMode,
            context = EvaluateExpressionSafetyGuard.Context(
                project = project,
                sourcePosition = frame.sourcePosition
            ),
            customRules = settings.customEvaluateExpressionBlockRules
        )
        if (safetyViolation != null) {
            return createErrorResult(safetyViolation.toUserMessage())
        }

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
        return when (val outcome = EvaluatorUtils.evaluate(evaluator, expression, EVALUATION_TIMEOUT_MS)) {
            is EvaluatorUtils.EvaluationOutcome.Timeout -> null

            is EvaluatorUtils.EvaluationOutcome.Failure -> EvaluationResult(
                expression = expression,
                value = "",
                type = "error",
                hasChildren = false,
                error = outcome.error
            )

            is EvaluatorUtils.EvaluationOutcome.Success -> {
                val presentation = VariablePresentationUtils.awaitPresentation(
                    outcome.value, PRESENTATION_TIMEOUT_MS
                )
                EvaluationResult(
                    expression = expression,
                    value = presentation?.value ?: VariablePresentationUtils.UNAVAILABLE_VALUE_TEXT,
                    type = presentation?.type ?: "unknown",
                    hasChildren = presentation?.hasChildren ?: false
                )
            }
        }
    }

    private companion object {
        const val EVALUATION_TIMEOUT_MS = 10000L
        const val PRESENTATION_TIMEOUT_MS = 5000L
    }
}
