package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.watch

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RemoveWatchResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
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

class RemoveWatchTool : AbstractMcpTool() {

    override val name = "remove_watch"

    override val description = """
        Removes a watch expression from the debug session.
        Use the watch_id from add_watch or the expression string to identify the watch.
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
                put("description", "ID of the watch to remove (from add_watch response)")
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
            ?: return createErrorResult("Missing required parameter: watch_id")
        val expression = arguments["expression"]?.jsonPrimitive?.content

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        return try {
            val removed = removeWatch(session, watchId, expression)

            if (removed) {
                createJsonResult(RemoveWatchResult(
                    sessionId = getSessionId(session),
                    watchId = watchId,
                    message = "Watch removed successfully"
                ))
            } else {
                createErrorResult("Watch not found: $watchId")
            }
        } catch (e: Exception) {
            createErrorResult("Failed to remove watch: ${e.message}")
        }
    }

    private suspend fun removeWatch(session: XDebugSession, watchId: String, expression: String?): Boolean {
        return withContext(Dispatchers.Main) {
            ApplicationManager.getApplication().runReadAction<Boolean> {
                val sessionImpl = session as? XDebugSessionImpl
                    ?: throw IllegalStateException("Session is not an XDebugSessionImpl")

                sessionImpl.sessionTab?.watchesView?.let { watchesView ->
                    try {
                        val removeMethod = watchesView.javaClass.methods.find {
                            it.name == "removeWatches" || it.name == "removeAllWatches"
                        }

                        if (expression != null) {
                            val getWatchesMethod = watchesView.javaClass.methods.find {
                                it.name == "getWatchExpressions"
                            }
                            val watches = getWatchesMethod?.invoke(watchesView) as? Array<*>

                            watches?.forEachIndexed { index, watch ->
                                val expr = watch?.toString()
                                if (expr == expression || expr?.hashCode()?.toString() == watchId) {
                                    removeMethod?.invoke(watchesView, listOf(index))
                                    return@runReadAction true
                                }
                            }
                        }

                        false
                    } catch (e: Exception) {
                        false
                    }
                } ?: false
            }
        }
    }
}
