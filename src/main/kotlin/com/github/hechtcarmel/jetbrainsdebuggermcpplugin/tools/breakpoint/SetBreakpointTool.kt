package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateExpressionSafetyGuard
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetBreakpointResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.LogMessagePart
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.LogMessageTransformer
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StableObjectIds
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.UnsupportedLogMessageException
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Sets a line breakpoint at a specified location.
 */
class SetBreakpointTool : AbstractMcpTool() {

    override val name = "set_breakpoint"

    override val description = """
        Sets a breakpoint at a specific file and line number, optionally with conditions or logging.
        Use to pause execution at specific code locations. Execution will stop when the breakpoint is hit (unless using log-only mode with suspend_policy='none').
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.idempotentMutable("Set Breakpoint")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("file_path", stringProperty("Absolute path to the file. Files inside JAR/ZIP archives are supported with the '!/' separator, e.g. '/path/to/lib-sources.jar!/com/example/Foo.kt' (the IDE's 'Copy Absolute Path' format for library sources)."))
            put("line", integerProperty("1-based line number", minimum = 1))
            put("condition", stringProperty("Boolean expression that must evaluate to true for the breakpoint to pause execution. Uses the target language syntax (e.g., 'count > 10', 'name.equals(\"test\")'). Evaluated each time the line is reached."))
            put("log_message", stringProperty("Message to log when breakpoint is hit (tracepoint). Use {expression} syntax to include evaluated values (e.g., 'x={x}, y={y}'). When set with suspend_policy='none', creates a non-stopping logpoint."))
            putJsonObject("suspend_policy") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("all"))
                    add(JsonPrimitive("thread"))
                    add(JsonPrimitive("none"))
                }
                put("description", "Thread suspend policy: 'all' suspends all threads (default), 'thread' suspends only the current thread, 'none' logs without stopping (use with log_message for logpoints)")
                put("default", "all")
            }
            put("enabled", booleanProperty("Whether breakpoint is enabled", default = true))
            put("temporary", booleanProperty("Remove after first hit", default = false))
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val filePath = ToolArguments.requireString(arguments, "file_path")
        val line = ToolArguments.requireInt(arguments, "line", min = 1)
        val condition = ToolArguments.optionalString(arguments, "condition")
        val logMessage = ToolArguments.optionalString(arguments, "log_message")
        val suspendPolicy = ToolArguments.optionalEnum(arguments, "suspend_policy", listOf("all", "thread", "none"))
        val enabled = ToolArguments.optionalBoolean(arguments, "enabled", default = true)
        val temporary = ToolArguments.optionalBoolean(arguments, "temporary", default = false)

        // Find the file
        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult(
                "File not found: $filePath. " +
                    "For files inside JAR archives, use the '!/' separator " +
                    "(e.g. /path/to/lib-sources.jar!/com/example/Foo.kt) and ensure the JAR " +
                    "is attached to the project as a library."
            )

        val breakpointManager = getDebuggerManager(project).breakpointManager
        val lineIndex = line - 1 // Convert to 0-based

        // Resolve the highest-priority line-breakpoint type that applies at this location.
        // "No applicable type" is exactly what "not a valid breakpoint location" means, so this
        // replaces the old canPutBreakpointAt pre-check.
        val breakpointType = runReadAction {
            XDebuggerUtil.getInstance().lineBreakpointTypes
                .filter { it.canPutAt(virtualFile, lineIndex, project) }
                .maxByOrNull { it.priority }
        } ?: return createErrorResult("Cannot set breakpoint at $filePath:$line (not a valid breakpoint location)")

        // Conditions and log expressions are evaluated by the debugger on every hit, unattended —
        // a strictly worse primitive than evaluate_expression — so they go through the same guard.
        checkExpressionSafety(project, virtualFile, lineIndex, condition, logMessage)?.let { return it }

        // Transform before touching the breakpoint manager so an unsupported log_message fails
        // fast without creating or modifying anything.
        val logExpression = logMessage?.let { msg ->
            try {
                LogMessageTransformer.transform(msg, virtualFile)
            } catch (e: UnsupportedLogMessageException) {
                return createErrorResult(e.message ?: "log_message placeholders are not supported for this file type")
            }
        }

        val fileLanguage = (virtualFile.fileType as? LanguageFileType)?.language

        return try {
            val breakpoint = withContext(Dispatchers.EDT) {
                WriteAction.compute<XLineBreakpoint<*>, RuntimeException> {
                    // findBreakpointAtLine first: re-setting the same line updates the existing
                    // breakpoint in place rather than toggling it off, and addLineBreakpoint is
                    // synchronous — no toggle race, no delay, no "created but reported failed".
                    val bp = findOrCreateBreakpoint(breakpointManager, breakpointType, virtualFile, lineIndex, temporary)

                    bp.isEnabled = enabled

                    condition?.let {
                        bp.conditionExpression = XDebuggerUtil.getInstance()
                            .createExpression(it, fileLanguage, null, EvaluationMode.EXPRESSION)
                    }

                    logExpression?.let {
                        bp.logExpressionObject = XDebuggerUtil.getInstance()
                            .createExpression(it, fileLanguage, null, EvaluationMode.EXPRESSION)
                    }

                    suspendPolicy?.let { policy ->
                        // optionalEnum already rejected anything outside {all, thread, none}
                        bp.suspendPolicy = when (policy) {
                            "none" -> SuspendPolicy.NONE
                            "thread" -> SuspendPolicy.THREAD
                            else -> SuspendPolicy.ALL
                        }
                    }

                    bp
                }
            }

            createJsonResult(SetBreakpointResult(
                breakpointId = StableObjectIds.idFor(breakpoint),
                status = "set",
                verified = true,
                message = "Breakpoint set at ${virtualFile.name}:$line",
                file = filePath,
                line = line
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to set breakpoint: ${e.message}")
        }
    }

    /**
     * Validates the condition and each pre-transform `{expr}` placeholder of the log message
     * against the evaluate-expression safety guard.
     *
     * The placeholders are validated individually rather than after transformation on purpose:
     * the transformed Kotlin/JS/Python form is itself an interpolated string template, which the
     * guard rejects wholesale — validating the parts keeps benign messages usable.
     *
     * @return an error result naming the blocked construct, or null when everything is allowed
     */
    private fun checkExpressionSafety(
        project: Project,
        virtualFile: VirtualFile,
        lineIndex: Int,
        condition: String?,
        logMessage: String?
    ): CallToolResult? {
        if (condition == null && logMessage == null) return null

        val settings = McpSettings.getInstance()
        val guardContext = EvaluateExpressionSafetyGuard.Context(
            project = project,
            sourcePosition = runReadAction { XDebuggerUtil.getInstance().createPosition(virtualFile, lineIndex) }
        )

        condition?.let { expr ->
            EvaluateExpressionSafetyGuard.validate(
                expression = expr,
                mode = settings.evaluateExpressionSafetyMode,
                context = guardContext,
                customRules = settings.customEvaluateExpressionBlockRules
            )?.let { violation ->
                return createErrorResult("Breakpoint condition rejected: ${violation.toUserMessage()}")
            }
        }

        logMessage?.let { msg ->
            LogMessageTransformer.parseLogMessage(msg)
                .filterIsInstance<LogMessagePart.Expression>()
                .forEach { part ->
                    EvaluateExpressionSafetyGuard.validate(
                        expression = part.expression,
                        mode = settings.evaluateExpressionSafetyMode,
                        context = guardContext,
                        customRules = settings.customEvaluateExpressionBlockRules
                    )?.let { violation ->
                        return createErrorResult(
                            "Breakpoint log_message expression '${part.expression}' rejected: ${violation.toUserMessage()}"
                        )
                    }
                }
        }

        return null
    }

    /**
     * Finds the line breakpoint of [type] at the location, or creates one synchronously.
     *
     * Must run inside a write action on the EDT.
     */
    @Suppress("UNCHECKED_CAST")
    private fun findOrCreateBreakpoint(
        breakpointManager: XBreakpointManager,
        type: XLineBreakpointType<*>,
        virtualFile: VirtualFile,
        lineIndex: Int,
        temporary: Boolean
    ): XLineBreakpoint<*> {
        val lineType = type as XLineBreakpointType<XBreakpointProperties<*>>
        return breakpointManager.findBreakpointsAtLine(lineType, virtualFile, lineIndex).firstOrNull()
            ?: breakpointManager.addLineBreakpoint(
                lineType,
                virtualFile.url,
                lineIndex,
                lineType.createBreakpointProperties(virtualFile, lineIndex),
                temporary
            )
    }
}
