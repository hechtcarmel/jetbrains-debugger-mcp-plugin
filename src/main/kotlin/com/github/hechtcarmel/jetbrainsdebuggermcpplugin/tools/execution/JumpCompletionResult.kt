package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JumpCompletionResult {
    /** Verification failed, not proof that the jump failed. Keep the conservative MCP flag. */
    fun unverified(sessionId: String, message: String): CallToolResult {
        // Existing ExecutionControlResult keys; its optional newState is deliberately omitted.
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("action", "jump_to_line")
            put("status", "completion_unverified")
            put("message", message)
        }
        return CallToolResult(
            content = listOf(TextContent(payload.toString())),
            structuredContent = payload,
            isError = true,
        )
    }
}
