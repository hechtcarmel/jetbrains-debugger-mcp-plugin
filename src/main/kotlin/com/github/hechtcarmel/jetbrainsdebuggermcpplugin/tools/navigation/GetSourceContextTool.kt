package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceContext
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceLine
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class GetSourceContextTool : AbstractMcpTool() {

    override val name = "get_source_context"

    override val description = """
        Returns source code lines around a specific location or the current execution point.
        Use to see the code context without switching to the IDE. Shows line numbers and indicates which lines have breakpoints.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Get Source Context")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("file_path", stringProperty("Absolute path to the source file. Files inside JAR/ZIP archives are supported with the '!/' separator, e.g. '/path/to/lib-sources.jar!/com/example/Foo.kt' (the IDE's 'Copy Absolute Path' format for library sources). If not provided, uses current debug position."))
            put("line", integerProperty("Center line number (1-based). If not provided with file_path, uses current position.", minimum = 1))
            put("lines_before", integerProperty("Number of source lines to include before the target line. Use larger values when you need more context to understand the code flow.", default = 5, minimum = 0))
            put("lines_after", integerProperty("Number of source lines to include after the target line. Use larger values to see more of the upcoming code.", default = 5, minimum = 0))
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val filePathArg = ToolArguments.optionalString(arguments, "file_path")
        val lineArg = ToolArguments.optionalIntOrNull(arguments, "line", min = 1)
        val linesBefore = ToolArguments.optionalInt(arguments, "lines_before", default = 5, min = 0)
        val linesAfter = ToolArguments.optionalInt(arguments, "lines_after", default = 5, min = 0)

        val filePath: String
        val centerLine: Int

        if (filePathArg != null && lineArg != null) {
            filePath = filePathArg
            centerLine = lineArg
        } else {
            val session = requireSession(
                project, sessionId,
                noSessionMessage = "No active debug session. Provide file_path and line instead."
            )

            val currentFrame = session.currentStackFrame
                ?: return createErrorResult("No current stack frame. Provide file_path and line instead.")

            val position = currentFrame.sourcePosition
                ?: return createErrorResult("No source position available. Provide file_path and line instead.")

            filePath = position.file.path
            centerLine = position.line + 1
        }

        val virtualFile = VirtualFileResolver.resolve(filePath)
            ?: return createErrorResult(
                "File not found: $filePath. " +
                    "For files inside JAR archives, use the '!/' separator " +
                    "(e.g. /path/to/lib-sources.jar!/com/example/Foo.kt)."
            )

        val (startLine, endLine, lines) = readAction {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: return@readAction null

            val start = maxOf(1, centerLine - linesBefore)
            val end = minOf(document.lineCount, centerLine + linesAfter)

            val sourceLines = (start..end).mapNotNull { lineNum ->
                try {
                    val lineIndex = lineNum - 1
                    if (lineIndex >= 0 && lineIndex < document.lineCount) {
                        val lineStart = document.getLineStartOffset(lineIndex)
                        val lineEnd = document.getLineEndOffset(lineIndex)
                        val content = document.getText(TextRange(lineStart, lineEnd))
                        SourceLine(
                            number = lineNum,
                            content = content,
                            isCurrent = lineNum == centerLine
                        )
                    } else null
                } catch (e: CancellationException) {
                    // Covers ProcessCanceledException: the suspending readAction cancels and
                    // retries this lambda when a write action arrives — that must propagate.
                    throw e
                } catch (e: Exception) {
                    null
                }
            }

            Triple(start, end, sourceLines)
        } ?: return createErrorResult("Cannot read file: $filePath")

        val breakpointManager = getDebuggerManager(project).breakpointManager
        val breakpointsInView = breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { bp ->
                bp.fileUrl == virtualFile.url &&
                bp.line + 1 in startLine..endLine
            }
            .map { it.line + 1 }

        return createJsonResult(SourceContext(
            file = filePath,
            startLine = startLine,
            endLine = endLine,
            currentLine = centerLine,
            lines = lines,
            breakpointsInView = breakpointsInView
        ))
    }
}
