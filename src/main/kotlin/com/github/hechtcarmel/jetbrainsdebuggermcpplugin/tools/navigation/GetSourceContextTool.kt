package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceContext
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceLine
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class GetSourceContextTool : AbstractMcpTool() {

    override val name = "get_source_context"

    override val description = """
        Returns source code lines around a specific location or the current execution point.
        Use to see the code context without switching to the IDE. Shows line numbers and indicates which lines have breakpoints.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("Get Source Context")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the source file. If not provided, uses current debug position.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "Center line number (1-based). If not provided with file_path, uses current position.")
                put("minimum", 1)
            }
            putJsonObject("lines_before") {
                put("type", "integer")
                put("description", "Number of source lines to include before the target line. Use larger values when you need more context to understand the code flow.")
                put("default", 5)
                put("minimum", 0)
            }
            putJsonObject("lines_after") {
                put("type", "integer")
                put("description", "Number of source lines to include after the target line. Use larger values to see more of the upcoming code.")
                put("default", 5)
                put("minimum", 0)
            }
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val filePathArg = arguments["file_path"]?.jsonPrimitive?.content
        val lineArg = arguments["line"]?.jsonPrimitive?.intOrNull
        val linesBefore = arguments["lines_before"]?.jsonPrimitive?.intOrNull ?: 5
        val linesAfter = arguments["lines_after"]?.jsonPrimitive?.intOrNull ?: 5

        val filePath: String
        val centerLine: Int

        if (filePathArg != null && lineArg != null) {
            filePath = filePathArg
            centerLine = lineArg
        } else {
            val session = resolveSession(project, sessionId)
                ?: return createErrorResult(
                    if (sessionId != null) "Session not found: $sessionId"
                    else "No active debug session. Provide file_path and line instead."
                )

            val currentFrame = session.currentStackFrame
                ?: return createErrorResult("No current stack frame. Provide file_path and line instead.")

            val position = currentFrame.sourcePosition
                ?: return createErrorResult("No source position available. Provide file_path and line instead.")

            filePath = position.file.path
            centerLine = position.line + 1
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return createErrorResult("File not found: $filePath")

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
