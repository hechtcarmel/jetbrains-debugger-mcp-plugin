package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateExpressionSafetyGuard
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetVariableResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.EvaluatorUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.FrameVariablesCollector
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VariablePresentationUtils
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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

        // `new_value` is evaluated as a code fragment by the same evaluator `evaluate_expression`
        // uses, so it has to clear the same safety gate. Without this, the blocklist could be
        // sidestepped entirely by passing the payload as a variable value instead of an
        // expression. Only the value is checked, not the synthesized `name = value` assignment:
        // the assignment is this tool's whole purpose, and validating it would make set_variable
        // unusable in every mode above Unrestricted.
        val settings = McpSettings.getInstance()
        val safetyViolation = EvaluateExpressionSafetyGuard.validate(
            expression = newValue,
            mode = settings.evaluateExpressionSafetyMode,
            context = EvaluateExpressionSafetyGuard.Context(
                project = project,
                sourcePosition = currentFrame.sourcePosition
            ),
            customRules = settings.customEvaluateExpressionBlockRules
        )
        if (safetyViolation != null) {
            return createErrorResult(safetyViolation.toUserMessage())
        }

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
        val children = FrameVariablesCollector.collectChildren(frame) ?: return null
        val match = children.firstOrNull { (name, _) -> name == targetName } ?: return null
        val presentation = VariablePresentationUtils.awaitPresentation(match.second)
        return VariableData(
            value = match.second,
            displayValue = presentation?.value ?: VariablePresentationUtils.UNAVAILABLE_VALUE_TEXT,
            type = presentation?.type ?: "unknown"
        )
    }

    private suspend fun evaluateAssignment(
        evaluator: XDebuggerEvaluator,
        assignmentExpression: String
    ): SetResult {
        return when (val outcome = EvaluatorUtils.evaluate(evaluator, assignmentExpression, ASSIGNMENT_TIMEOUT_MS)) {
            is EvaluatorUtils.EvaluationOutcome.Timeout ->
                SetResult(success = false, error = "Timeout while setting variable value")

            is EvaluatorUtils.EvaluationOutcome.Failure ->
                SetResult(success = false, error = outcome.error)

            is EvaluatorUtils.EvaluationOutcome.Success -> {
                val presentation = VariablePresentationUtils.awaitPresentation(outcome.value)
                SetResult(success = true, resultValue = presentation?.value)
            }
        }
    }

    private companion object {
        const val ASSIGNMENT_TIMEOUT_MS = 5000L
    }
}
