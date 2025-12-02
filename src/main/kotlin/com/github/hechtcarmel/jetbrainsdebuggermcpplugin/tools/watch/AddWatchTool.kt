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
            val success = addWatch(session, expression)

            if (success) {
                val watchInfo = WatchInfo(
                    id = expression,  // Use expression as ID for consistent lookup
                    expression = expression,
                    value = if (session.isPaused) "evaluating..." else "not evaluated (session running)"
                )

                createJsonResult(AddWatchResult(
                    sessionId = getSessionId(session),
                    watch = watchInfo,
                    message = "Watch expression added: $expression"
                ))
            } else {
                createErrorResult("Failed to add watch expression")
            }
        } catch (e: Exception) {
            createErrorResult("Failed to add watch: ${e.message}")
        }
    }

    private suspend fun addWatch(session: XDebugSession, expression: String): Boolean {
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { continuation ->
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val sessionImpl = session as? XDebugSessionImpl
                        if (sessionImpl == null) {
                            continuation.resume(false)
                            return@invokeLater
                        }

                        val watchExpression = XExpressionImpl.fromText(expression)
                        val watchesView = sessionImpl.sessionTab?.watchesView

                        if (watchesView != null) {
                            // Find addWatchExpression(XExpression, int, boolean) method
                            val addMethod = watchesView.javaClass.methods.find {
                                it.name == "addWatchExpression" &&
                                        it.parameterCount == 3 &&
                                        it.parameterTypes[0].name.contains("XExpression")
                            }

                            if (addMethod != null) {
                                addMethod.isAccessible = true
                                // Add at end (-1), navigate to watch node (true)
                                addMethod.invoke(watchesView, watchExpression, -1, true)
                                continuation.resume(true)
                            } else {
                                // Fallback: Try 4-parameter version with noDuplicates
                                val altMethod = watchesView.javaClass.methods.find {
                                    it.name == "addWatchExpression" &&
                                            it.parameterCount == 4
                                }
                                if (altMethod != null) {
                                    altMethod.isAccessible = true
                                    altMethod.invoke(watchesView, watchExpression, -1, true, true)
                                    continuation.resume(true)
                                } else {
                                    continuation.resume(false)
                                }
                            }
                        } else {
                            continuation.resume(false)
                        }
                    } catch (e: Exception) {
                        continuation.resume(false)
                    }
                }
            }
        } ?: false
    }
}
