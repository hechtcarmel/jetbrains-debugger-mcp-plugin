package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val result: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

object JsonRpcErrorCodes {
    // Standard JSON-RPC 2.0 error codes
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Custom error codes for debugger MCP
    const val NO_DEBUG_SESSION = -32001
    const val SESSION_NOT_FOUND = -32002
    const val BREAKPOINT_ERROR = -32003
    const val EVALUATION_ERROR = -32004
    const val MULTIPLE_PROJECTS = -32005
    const val PROJECT_NOT_FOUND = -32006
    const val RUN_CONFIG_NOT_FOUND = -32007
    const val EXECUTION_ERROR = -32008
}

object JsonRpcMethods {
    const val INITIALIZE = "initialize"
    const val NOTIFICATIONS_INITIALIZED = "notifications/initialized"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val PING = "ping"
}
