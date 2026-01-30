package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetBreakpointResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.LogMessageTransformer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    override val annotations = ToolAnnotations.idempotentMutable("Set Breakpoint")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the file")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
                put("minimum", 1)
            }
            putJsonObject("condition") {
                put("type", "string")
                put("description", "Boolean expression that must evaluate to true for the breakpoint to pause execution. Uses the target language syntax (e.g., 'count > 10', 'name.equals(\"test\")'). Evaluated each time the line is reached.")
            }
            putJsonObject("log_message") {
                put("type", "string")
                put("description", "Message to log when breakpoint is hit (tracepoint). Use {expression} syntax to include evaluated values (e.g., 'x={x}, y={y}'). When set with suspend_policy='none', creates a non-stopping logpoint.")
            }
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
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "Whether breakpoint is enabled")
                put("default", true)
            }
            putJsonObject("temporary") {
                put("type", "boolean")
                put("description", "Remove after first hit")
                put("default", false)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file_path"))
            add(JsonPrimitive("line"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments["file_path"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file_path")
        val line = arguments["line"]?.jsonPrimitive?.intOrNull
            ?: return createErrorResult("Missing required parameter: line")
        val condition = arguments["condition"]?.jsonPrimitive?.content
        val logMessage = arguments["log_message"]?.jsonPrimitive?.content
        val suspendPolicy = arguments["suspend_policy"]?.jsonPrimitive?.content
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val temporary = arguments["temporary"]?.jsonPrimitive?.booleanOrNull ?: false

        // Find the file
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return createErrorResult("File not found: $filePath")

        val breakpointManager = getDebuggerManager(project).breakpointManager
        val lineIndex = line - 1 // Convert to 0-based

        // Check if we can put a breakpoint at this location
        val canPut = runReadAction {
            XDebuggerUtil.getInstance().canPutBreakpointAt(project, virtualFile, lineIndex)
        }

        if (!canPut) {
            return createErrorResult("Cannot set breakpoint at $filePath:$line (not a valid breakpoint location)")
        }

        return try {
            // First check if a breakpoint already exists at this location
            var existingBreakpoint = findBreakpointAtLine(breakpointManager, virtualFile, lineIndex)

            if (existingBreakpoint == null) {
                // Use XDebuggerUtil.toggleLineBreakpoint which properly handles type resolution
                // and integrates with the debugger infrastructure
                withContext(Dispatchers.Main) {
                    ApplicationManager.getApplication().invokeAndWait {
                        // toggleLineBreakpoint is the same method called when clicking in the gutter
                        // It properly resolves the breakpoint type using getBreakpointTypeByPosition()
                        XDebuggerUtil.getInstance().toggleLineBreakpoint(
                            project,
                            virtualFile,
                            lineIndex,
                            temporary
                        )
                    }
                }

                // Small delay to allow async breakpoint creation to complete
                kotlinx.coroutines.delay(100)
            }

            // Find the breakpoint (either existing or just created)
            val breakpoint = findBreakpointAtLine(breakpointManager, virtualFile, lineIndex)

            if (breakpoint == null) {
                return createErrorResult("Failed to create breakpoint at $filePath:$line")
            }

            // Configure breakpoint properties
            withContext(Dispatchers.Main) {
                ApplicationManager.getApplication().runWriteAction {
                    breakpoint.isEnabled = enabled

                    condition?.let {
                        breakpoint.conditionExpression = XDebuggerUtil.getInstance().createExpression(it, null, null, EvaluationMode.EXPRESSION)
                    }

                    logMessage?.let { msg ->
                        val transformedExpression = LogMessageTransformer.transform(msg, virtualFile)
                        breakpoint.logExpressionObject = XDebuggerUtil.getInstance().createExpression(transformedExpression, null, null, EvaluationMode.EXPRESSION)
                    }

                    suspendPolicy?.let { policy ->
                        breakpoint.suspendPolicy = when (policy.lowercase()) {
                            "none" -> com.intellij.xdebugger.breakpoints.SuspendPolicy.NONE
                            "thread" -> com.intellij.xdebugger.breakpoints.SuspendPolicy.THREAD
                            else -> com.intellij.xdebugger.breakpoints.SuspendPolicy.ALL
                        }
                    }
                }
            }

            createJsonResult(SetBreakpointResult(
                breakpointId = breakpoint.hashCode().toString(),
                status = "set",
                verified = true,
                message = "Breakpoint set at ${virtualFile.name}:$line",
                file = filePath,
                line = line
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to set breakpoint: ${e.message}")
        }
    }

    /**
     * Find a line breakpoint at the specified file and line.
     */
    private fun findBreakpointAtLine(
        breakpointManager: com.intellij.xdebugger.breakpoints.XBreakpointManager,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        lineIndex: Int
    ): XLineBreakpoint<*>? {
        return breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .firstOrNull { bp ->
                bp.fileUrl == virtualFile.url && bp.line == lineIndex
            }
    }
}
