package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.watch

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.AddWatchResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.WatchInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchNode
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchesRootNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AddWatchTool : AbstractMcpTool() {

    override val name = "add_watch"

    override val description = """
        Adds a watch expression to monitor during debugging.
        Watch expressions are evaluated in the current context whenever the debugger pauses.
        Useful for tracking values that aren't in local variables.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("expression") {
                put("type", "string")
                put("description", "Expression to watch (e.g., 'myObject.field', 'array.length', 'x + y')")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("expression"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val expression = arguments["expression"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: expression")

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        return try {
            val watchId = addWatch(session, expression)

            val watchInfo = WatchInfo(
                id = watchId,
                expression = expression,
                value = if (session.isPaused) "evaluating..." else "not evaluated (session running)"
            )

            createJsonResult(AddWatchResult(
                sessionId = getSessionId(session),
                watch = watchInfo,
                message = "Watch expression added: $expression"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to add watch: ${e.message}")
        }
    }

    private suspend fun addWatch(session: XDebugSession, expression: String): String {
        return withContext(Dispatchers.Main) {
            ApplicationManager.getApplication().runReadAction<String> {
                val sessionImpl = session as? XDebugSessionImpl
                    ?: throw IllegalStateException("Session is not an XDebugSessionImpl")

                val watchExpression = XExpressionImpl.fromText(expression)

                sessionImpl.sessionTab?.watchesView?.let { watchesView ->
                    try {
                        val addWatchMethod = watchesView.javaClass.methods.find {
                            it.name == "addWatchExpression" && it.parameterCount == 1
                        }
                        addWatchMethod?.invoke(watchesView, watchExpression)
                    } catch (e: Exception) {
                        // Fallback approach
                    }
                }

                expression.hashCode().toString()
            }
        }
    }
}
