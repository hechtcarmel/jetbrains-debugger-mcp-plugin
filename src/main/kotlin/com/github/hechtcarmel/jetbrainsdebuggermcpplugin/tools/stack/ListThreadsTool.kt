package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ThreadInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ThreadListResult
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

class ListThreadsTool : AbstractMcpTool() {

    override val name = "list_threads"

    override val description = """
        Lists all threads in the debugged process.
        Returns thread IDs, names, states, and which thread is current.
        Use to understand the multi-threaded state of the application.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
        }
        put("required", buildJsonArray { })
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to list threads")
        }

        val suspendContext = session.suspendContext
            ?: return createErrorResult("No suspend context available")

        val threads = getThreads(suspendContext)
        val activeStack = suspendContext.activeExecutionStack

        val threadInfos = threads.mapIndexed { index, stack ->
            ThreadInfo(
                id = stack.hashCode().toString(),
                name = stack.displayName,
                state = if (stack == activeStack) "paused" else "suspended",
                isCurrent = stack == activeStack
            )
        }

        return createJsonResult(ThreadListResult(
            sessionId = getSessionId(session),
            threads = threadInfos,
            currentThreadId = activeStack?.hashCode()?.toString()
        ))
    }

    private suspend fun getThreads(suspendContext: XSuspendContext): List<XExecutionStack> {
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { continuation ->
                val stacks = mutableListOf<XExecutionStack>()

                suspendContext.activeExecutionStack?.let { stacks.add(it) }

                suspendContext.computeExecutionStacks(object : XSuspendContext.XExecutionStackContainer {
                    override fun addExecutionStack(
                        executionStacks: MutableList<out XExecutionStack>,
                        last: Boolean
                    ) {
                        for (stack in executionStacks) {
                            if (!stacks.any { it.hashCode() == stack.hashCode() }) {
                                stacks.add(stack)
                            }
                        }
                        if (last) {
                            continuation.resume(stacks)
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        continuation.resume(stacks)
                    }
                })
            }
        } ?: listOfNotNull(suspendContext.activeExecutionStack)
    }
}
