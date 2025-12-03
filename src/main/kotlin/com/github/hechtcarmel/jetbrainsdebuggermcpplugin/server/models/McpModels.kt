package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val description: String? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolCapability? = ToolCapability()
)

@Serializable
data class ToolCapability(
    val listChanged: Boolean = false
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: ServerInfo
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val annotations: ToolAnnotations? = null
)

/**
 * Tool annotations provide hints about tool behavior to help clients
 * categorize and present tools appropriately.
 *
 * Based on MCP specification 2025-06-18.
 */
@Serializable
data class ToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null
) {
    companion object {
        /**
         * Annotations for read-only inspection tools (list, get, etc.)
         */
        fun readOnly(title: String) = ToolAnnotations(
            title = title,
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

        /**
         * Annotations for state-changing tools that are NOT idempotent
         */
        fun mutable(title: String, destructive: Boolean = false) = ToolAnnotations(
            title = title,
            readOnlyHint = false,
            destructiveHint = destructive,
            idempotentHint = false,
            openWorldHint = false
        )

        /**
         * Annotations for state-changing tools that ARE idempotent
         */
        fun idempotentMutable(title: String, destructive: Boolean = false) = ToolAnnotations(
            title = title,
            readOnlyHint = false,
            destructiveHint = destructive,
            idempotentHint = true,
            openWorldHint = false
        )
    }
}

@Serializable
data class ToolsListResult(
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false,
    val structuredContent: JsonObject? = null
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String
    ) : ContentBlock()
}

fun textContent(text: String): ContentBlock = ContentBlock.Text(text = text)

fun successResult(text: String): ToolCallResult = ToolCallResult(
    content = listOf(textContent(text)),
    isError = false
)

fun errorResult(message: String): ToolCallResult = ToolCallResult(
    content = listOf(textContent(message)),
    isError = true
)
