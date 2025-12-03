package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Abstract base class for MCP debugger tools providing common functionality.
 *
 * This class provides:
 * - Debugger access helpers ([getDebuggerManager], [getCurrentSession], [resolveSession])
 * - Thread-safe operations ([readAction], [writeAction], [suspendingWriteAction])
 * - File resolution ([resolveFile])
 * - Result creation ([createSuccessResult], [createErrorResult], [createJsonResult])
 * - Cancellation checking ([checkCanceled])
 *
 * ## Usage
 *
 * Extend this class and implement [doExecute]:
 *
 * ```kotlin
 * class MyTool : AbstractMcpTool() {
 *     override val name = "my_tool"
 *     override val description = "My tool description"
 *     override val inputSchema = buildJsonObject { /* schema */ }
 *
 *     override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
 *         val session = resolveSession(project, arguments["session_id"]?.jsonPrimitive?.contentOrNull)
 *             ?: return createErrorResult("No active debug session")
 *         // Tool logic here
 *         return createSuccessResult("Done")
 *     }
 * }
 * ```
 *
 * @see McpTool
 * @see doExecute
 */
abstract class AbstractMcpTool : McpTool {

    /**
     * Default annotations for tools. Subclasses should override this
     * with appropriate values based on the tool's behavior.
     *
     * Default is read-only and idempotent as a safe default.
     */
    override val annotations: ToolAnnotations = ToolAnnotations.readOnly("Tool")

    /**
     * JSON serializer configured for tool results.
     * - Ignores unknown keys for forward compatibility
     * - Encodes default values
     * - Compact output (no pretty printing)
     */
    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        /**
         * Creates the `project_path` property definition for tool input schemas.
         *
         * All tools should include this property to support multi-project scenarios.
         * The property is optional - if omitted and only one project is open,
         * that project is used automatically.
         *
         * @return A pair of property name and JSON Schema definition
         */
        fun projectPathProperty(): Pair<String, JsonObject> {
            return "project_path" to buildJsonObject {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open, optional otherwise.")
            }
        }

        /**
         * Creates the `session_id` property definition for tool input schemas.
         *
         * Tools that operate on debug sessions should include this property.
         * If omitted, the current session is used.
         *
         * @return A pair of property name and JSON Schema definition
         */
        fun sessionIdProperty(): Pair<String, JsonObject> {
            return "session_id" to buildJsonObject {
                put("type", "string")
                put("description", "Debug session ID. Uses current session if omitted.")
            }
        }

        /**
         * Creates an integer property with min/max bounds and optional default.
         *
         * @param description The property description
         * @param default The default value (null if no default)
         * @param minimum The minimum allowed value (null if no minimum)
         * @param maximum The maximum allowed value (null if no maximum)
         * @return JSON Schema definition for the property
         */
        fun integerProperty(
            description: String,
            default: Int? = null,
            minimum: Int? = null,
            maximum: Int? = null
        ): JsonObject = buildJsonObject {
            put("type", "integer")
            put("description", description)
            default?.let { put("default", it) }
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

        /**
         * Creates a boolean property with optional default.
         *
         * @param description The property description
         * @param default The default value (null if no default)
         * @return JSON Schema definition for the property
         */
        fun booleanProperty(
            description: String,
            default: Boolean? = null
        ): JsonObject = buildJsonObject {
            put("type", "boolean")
            put("description", description)
            default?.let { put("default", it) }
        }

        /**
         * Creates a string property with optional default.
         *
         * @param description The property description
         * @param default The default value (null if no default)
         * @return JSON Schema definition for the property
         */
        fun stringProperty(
            description: String,
            default: String? = null
        ): JsonObject = buildJsonObject {
            put("type", "string")
            put("description", description)
            default?.let { put("default", it) }
        }
    }

    /**
     * Template method that delegates to tool-specific logic.
     *
     * @param project The IntelliJ project context
     * @param arguments The tool arguments as a JSON object
     * @return A [ToolCallResult] containing the operation result or error
     */
    final override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        return doExecute(project, arguments)
    }

    /**
     * Implement this method with the tool's specific execution logic.
     *
     * @param project The IntelliJ project context
     * @param arguments The tool arguments as a JSON object matching [inputSchema]
     * @return A [ToolCallResult] containing the operation result or error
     */
    protected abstract suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult

    // ========== Debugger Access Helpers ==========

    /**
     * Gets the XDebuggerManager for the project.
     *
     * @param project The project context
     * @return The XDebuggerManager instance
     */
    protected fun getDebuggerManager(project: Project): XDebuggerManager {
        return XDebuggerManager.getInstance(project)
    }

    /**
     * Gets the current (focused) debug session.
     *
     * @param project The project context
     * @return The current debug session, or null if none
     */
    protected fun getCurrentSession(project: Project): XDebugSession? {
        return getDebuggerManager(project).currentSession
    }

    /**
     * Gets all active debug sessions.
     *
     * @param project The project context
     * @return Array of all debug sessions
     */
    protected fun getAllSessions(project: Project): Array<out XDebugSession> {
        return getDebuggerManager(project).debugSessions
    }

    /**
     * Finds a debug session by its ID.
     *
     * Session IDs are the hash codes of the session objects.
     *
     * @param project The project context
     * @param sessionId The session ID (hash code as string)
     * @return The matching session, or null if not found
     */
    protected fun getSessionById(project: Project, sessionId: String): XDebugSession? {
        return getAllSessions(project).find {
            it.hashCode().toString() == sessionId
        }
    }

    /**
     * Resolves a debug session by ID, or returns the current session if no ID provided.
     *
     * This is the recommended method for tools that accept an optional session_id parameter.
     *
     * @param project The project context
     * @param sessionId Optional session ID. If null, returns current session.
     * @return The resolved session, or null if no session available
     */
    protected fun resolveSession(project: Project, sessionId: String?): XDebugSession? {
        return if (sessionId != null) {
            getSessionById(project, sessionId)
        } else {
            getCurrentSession(project)
        }
    }

    /**
     * Gets the session ID (hash code as string) for a session.
     *
     * @param session The debug session
     * @return The session ID string
     */
    protected fun getSessionId(session: XDebugSession): String {
        return session.hashCode().toString()
    }

    // ========== Thread Safety Helpers ==========

    /**
     * Executes an action with a read lock (blocking version).
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected fun <T> readAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    /**
     * Checks if the current operation has been cancelled.
     *
     * Call this frequently in long-running loops to allow cancellation.
     * Throws ProcessCanceledException if cancellation is requested.
     */
    protected fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

    /**
     * Executes an action with a write lock (blocking version).
     *
     * @param project The project context
     * @param commandName Name for the undo command
     * @param action The action to execute
     */
    protected fun writeAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
    }

    /**
     * Executes a write action using suspend function (non-blocking for caller).
     *
     * Runs the action on EDT with proper write locking.
     *
     * @param project The project context
     * @param commandName Name for the undo command
     * @param action The action to execute
     */
    protected suspend fun suspendingWriteAction(
        project: Project,
        commandName: String,
        action: () -> Unit
    ) {
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
        }
    }

    /**
     * Executes an action on the EDT (Event Dispatch Thread).
     *
     * Use this for UI operations or debugger operations that require EDT.
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected suspend fun <T> onEdt(action: () -> T): T {
        return withContext(Dispatchers.EDT) {
            action()
        }
    }

    // ========== File Resolution Helpers ==========

    /**
     * Resolves a file path to a [VirtualFile].
     *
     * @param project The project context
     * @param relativePath Path relative to project root, or absolute path
     * @return The VirtualFile, or null if not found
     */
    protected fun resolveFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
    }

    /**
     * Converts an absolute file path to a project-relative path.
     *
     * @param project The project context
     * @param virtualFile The file
     * @return The relative path, or absolute path if not under project root
     */
    protected fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        val basePath = project.basePath ?: return virtualFile.path
        return virtualFile.path.removePrefix(basePath).removePrefix("/")
    }

    /**
     * Gets the text content of a specific line from a document.
     *
     * @param document The document
     * @param line 1-based line number
     * @return The line text, or empty string if line is invalid
     */
    protected fun getLineText(document: Document, line: Int): String {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return ""

        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.getText(TextRange(startOffset, endOffset))
    }

    // ========== Result Creation Helpers ==========

    /**
     * Creates a successful result with a text message.
     *
     * @param text The success message
     * @return A [ToolCallResult] with `isError = false`
     */
    protected fun createSuccessResult(text: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = text)),
            isError = false
        )
    }

    /**
     * Creates an error result with a message.
     *
     * @param message The error message
     * @return A [ToolCallResult] with `isError = true`
     */
    protected fun createErrorResult(message: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = message)),
            isError = true
        )
    }

    /**
     * Creates a successful result with JSON-serialized data.
     *
     * When the data serializes to a JSON object, it is also included as
     * `structuredContent` for MCP tools that define an `outputSchema`.
     *
     * @param data The data to serialize (must be @Serializable)
     * @return A [ToolCallResult] with JSON content, optional structuredContent, and `isError = false`
     */
    protected inline fun <reified T> createJsonResult(data: T): ToolCallResult {
        val jsonText = json.encodeToString(data)
        val jsonElement = json.parseToJsonElement(jsonText)
        val structuredContent = jsonElement as? JsonObject
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = jsonText)),
            isError = false,
            structuredContent = structuredContent
        )
    }
}
