package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.watch

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RemoveWatchResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.frame.XWatchesView
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchNode
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchesRootNode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

class RemoveWatchTool : AbstractMcpTool() {

    override val name = "remove_watch"

    override val description = """
        Removes a watch expression from the debug session.
        Use the expression string to identify the watch to remove.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("watch_id") {
                put("type", "string")
                put("description", "ID of the watch to remove (the expression string from add_watch response)")
            }
            putJsonObject("expression") {
                put("type", "string")
                put("description", "Expression of the watch to remove (alternative to watch_id)")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("watch_id"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val watchId = arguments["watch_id"]?.jsonPrimitive?.content
        val expression = arguments["expression"]?.jsonPrimitive?.content

        // Either watch_id or expression must be provided
        val targetExpression = watchId ?: expression
            ?: return createErrorResult("Missing required parameter: watch_id or expression")

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        return try {
            val removed = removeWatch(session, targetExpression)

            if (removed) {
                createJsonResult(RemoveWatchResult(
                    sessionId = getSessionId(session),
                    watchId = targetExpression,
                    message = "Watch removed successfully"
                ))
            } else {
                createErrorResult("Watch not found: $targetExpression")
            }
        } catch (e: Exception) {
            createErrorResult("Failed to remove watch: ${e.message}")
        }
    }

    private suspend fun removeWatch(session: XDebugSession, targetExpression: String): Boolean {
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { continuation ->
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val sessionImpl = session as? XDebugSessionImpl
                        if (sessionImpl == null) {
                            continuation.resume(false)
                            return@invokeLater
                        }

                        val watchesView = sessionImpl.sessionTab?.watchesView
                        if (watchesView == null) {
                            continuation.resume(false)
                            return@invokeLater
                        }

                        // Get current watch expressions
                        val getExpressionsMethod = watchesView.javaClass.methods.find {
                            it.name == "getWatchExpressions" && it.parameterCount == 0
                        }

                        if (getExpressionsMethod != null) {
                            getExpressionsMethod.isAccessible = true
                            val expressions = getExpressionsMethod.invoke(watchesView)

                            if (expressions is Array<*>) {
                                // Find the index of the expression to remove
                                var indexToRemove = -1
                                for (i in expressions.indices) {
                                    val expr = expressions[i]
                                    val exprText = expr?.toString() ?: continue
                                    // XExpression.toString() may include type info, so check contains
                                    if (exprText == targetExpression || exprText.contains(targetExpression)) {
                                        indexToRemove = i
                                        break
                                    }
                                }

                                if (indexToRemove >= 0) {
                                    // Try to remove by index using removeWatches or similar method
                                    val removeMethod = watchesView.javaClass.methods.find {
                                        it.name == "removeWatches" && it.parameterCount == 1
                                    }

                                    if (removeMethod != null) {
                                        removeMethod.isAccessible = true
                                        // The method typically takes a list of nodes or indices
                                        try {
                                            removeMethod.invoke(watchesView, listOf(indexToRemove))
                                            continuation.resume(true)
                                            return@invokeLater
                                        } catch (e: Exception) {
                                            // Try with different argument types
                                        }
                                    }

                                    // Alternative: Build new expression list without the target
                                    val setExpressionsMethod = watchesView.javaClass.methods.find {
                                        it.name == "setWatchExpressions" && it.parameterCount == 1
                                    }

                                    if (setExpressionsMethod != null) {
                                        setExpressionsMethod.isAccessible = true
                                        val newExpressions = expressions.filterIndexed { idx, _ -> idx != indexToRemove }
                                        val arrayType = expressions.javaClass.componentType
                                        val newArray = java.lang.reflect.Array.newInstance(arrayType, newExpressions.size)
                                        newExpressions.forEachIndexed { idx, expr ->
                                            java.lang.reflect.Array.set(newArray, idx, expr)
                                        }
                                        setExpressionsMethod.invoke(watchesView, newArray)
                                        continuation.resume(true)
                                        return@invokeLater
                                    }
                                }
                            }
                        }

                        continuation.resume(false)
                    } catch (e: Exception) {
                        continuation.resume(false)
                    }
                }
            }
        } ?: false
    }
}
