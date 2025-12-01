package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ServerInfo(
    val name: String,
    val version: String
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = ToolsCapability()
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = false
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
    val inputSchema: JsonObject
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false
)

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val type: String = "text",
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
