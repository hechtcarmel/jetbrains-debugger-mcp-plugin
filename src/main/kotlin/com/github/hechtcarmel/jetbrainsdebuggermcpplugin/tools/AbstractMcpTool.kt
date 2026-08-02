package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StableObjectIds
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
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
 * - Precondition helpers that fail with the pinned error strings ([requireSession], [requirePausedSession])
 * - Thread-safe operations ([readAction], [onEdt])
 * - Result creation ([createSuccessResult], [createErrorResult], [createJsonResult])
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
 *         val sessionId = ToolArguments.optionalString(arguments, "session_id")
 *         val session = requirePausedSession(project, sessionId, "do my thing")
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
    override val annotations: ToolAnnotations = ToolAnnotationPresets.readOnly("Tool")

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
     * A [ToolExecutionError] thrown by the boundary helpers ([requireSession],
     * [requirePausedSession], `ToolArguments`) is converted here into the standard
     * `isError: true` result — its message is the complete client-facing error text.
     *
     * @param project The IntelliJ project context
     * @param arguments The tool arguments as a JSON object
     * @return A [CallToolResult] containing the operation result or error
     */
    final override suspend fun execute(project: Project, arguments: JsonObject): CallToolResult {
        return try {
            doExecute(project, arguments)
        } catch (e: ToolExecutionError) {
            createErrorResult(e.message)
        }
    }

    /**
     * Implement this method with the tool's specific execution logic.
     *
     * @param project The IntelliJ project context
     * @param arguments The tool arguments as a JSON object matching [inputSchema]
     * @return A [CallToolResult] containing the operation result or error
     */
    protected abstract suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult

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
     * Session IDs are opaque strings minted by [StableObjectIds] — unique per live session, unlike
     * the `hashCode()`-derived IDs they replaced, which could collide.
     *
     * @param project The project context
     * @param sessionId The session ID as returned by a previous tool call
     * @return The matching session, or null if not found
     */
    protected fun getSessionById(project: Project, sessionId: String): XDebugSession? {
        return getAllSessions(project).find {
            StableObjectIds.idFor(it) == sessionId
        }
    }

    /**
     * Resolves a debug session by ID, or returns the current session if no ID provided.
     *
     * This is the recommended method for tools that accept an optional session_id parameter.
     *
     * @param project The project context
     * @param sessionId Optional session ID. If null, returns the current session — or, when the
     *   IDE has no notion of a "current" one, the only session there is.
     * @return The resolved session, or null if no session available
     */
    protected fun resolveSession(project: Project, sessionId: String?): XDebugSession? {
        if (sessionId != null) return getSessionById(project, sessionId)
        // currentSession tracks debugger-UI focus and is only assigned once a session pauses or
        // its tab is selected. A session that is running and has never paused therefore is not
        // "current" — and pause_execution against it would report "No active debug session"
        // even though exactly one session exists. A single session is unambiguous; use it.
        return getCurrentSession(project) ?: getAllSessions(project).singleOrNull { !it.isStopped }
    }

    /**
     * Gets the opaque session ID for a session (see [StableObjectIds]).
     *
     * @param session The debug session
     * @return The session ID string
     */
    protected fun getSessionId(session: XDebugSession): String {
        return StableObjectIds.idFor(session)
    }

    /**
     * Resolves the session like [resolveSession], or fails with the pinned error strings:
     * `Session not found: <id>` when an ID was given, otherwise [noSessionMessage]
     * (default `No active debug session`).
     *
     * The thrown [ToolExecutionError] is converted to an `isError: true` result by [execute],
     * which is what lets ~14 formerly hand-copied resolve-or-return preambles collapse to one
     * expression without changing a single wire string.
     */
    protected fun requireSession(
        project: Project,
        sessionId: String?,
        noSessionMessage: String = "No active debug session"
    ): XDebugSession {
        return resolveSession(project, sessionId)
            ?: throw ToolExecutionError(
                if (sessionId != null) "Session not found: $sessionId"
                else noSessionMessage
            )
    }

    /**
     * Like [requireSession], and additionally fails with the pinned
     * `Session must be paused to <verb>` when the session is not paused.
     *
     * @param verb the tool-specific phrase, e.g. `"step over"`, `"evaluate expressions"` —
     *   each caller passes its historical wording verbatim, because the resulting strings are
     *   the client contract
     */
    protected fun requirePausedSession(project: Project, sessionId: String?, verb: String): XDebugSession {
        val session = requireSession(project, sessionId)
        if (!session.isPaused) {
            throw ToolExecutionError("Session must be paused to $verb")
        }
        return session
    }

    // ========== Thread Safety Helpers ==========

    /**
     * Executes an action under the read lock, suspending instead of blocking.
     *
     * Delegates to the platform's cancellable [com.intellij.openapi.application.readAction],
     * which yields to pending write actions instead of stalling the EDT behind a held lock.
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected suspend fun <T> readAction(action: () -> T): T {
        return com.intellij.openapi.application.readAction(action)
    }

    /**
     * Executes an action on the EDT (Event Dispatch Thread).
     *
     * Use this for UI operations or debugger operations that require EDT.
     *
     * This is the one sanctioned EDT hop for tools. Do NOT use
     * `ApplicationManager.getApplication().invokeAndWait { }` in suspend code: it dispatches with
     * `ModalityState.defaultModalityState()` and ignores the `ModalityState.any()` context element
     * the server installs around every tool call, so the runnable queues behind any open modal
     * dialog and the tool call hangs until the user closes it. `Dispatchers.EDT` reads the
     * coroutine's modality context element and runs regardless of open dialogs.
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected suspend fun <T> onEdt(action: () -> T): T {
        return withContext(Dispatchers.EDT) {
            action()
        }
    }

    // ========== Result Creation Helpers ==========

    /**
     * Creates a successful result with a text message.
     *
     * `isError` is passed explicitly rather than left to default. [CallToolResult.isError] is
     * `Boolean?` and the SDK serializer omits nulls, so an implicit default would drop the key
     * from the wire entirely — a silent break for every client that branches on it.
     * Pinned by `McpSdkAssumptionsTest`.
     *
     * @param text The success message
     * @return A [CallToolResult] with `isError = false`
     */
    protected fun createSuccessResult(text: String): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text)),
            isError = false
        )
    }

    /**
     * Creates an error result with a message.
     *
     * Tool failures are reported as a *successful* MCP result carrying `isError = true`, never as a
     * JSON-RPC error — the message is the only failure signal a model can read and act on.
     *
     * @param message The error message
     * @return A [CallToolResult] with `isError = true`
     */
    protected fun createErrorResult(message: String): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(message)),
            isError = true
        )
    }

    /**
     * Creates a successful result with JSON-serialized data.
     *
     * When the data serializes to a JSON object it is also included as `structuredContent`, which
     * is what MCP clients validate against a declared `outputSchema`.
     *
     * @param data The data to serialize (must be @Serializable)
     * @return A [CallToolResult] with JSON content, optional structuredContent, and `isError = false`
     */
    protected inline fun <reified T> createJsonResult(data: T): CallToolResult {
        val jsonText = json.encodeToString(data)
        val jsonElement = json.parseToJsonElement(jsonText)
        val structuredContent = jsonElement as? JsonObject
        return CallToolResult(
            content = listOf(TextContent(jsonText)),
            isError = false,
            structuredContent = structuredContent
        )
    }
}
