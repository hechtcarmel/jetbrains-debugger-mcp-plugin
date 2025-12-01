package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ServerInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.ide.BuiltInServerManager

@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val log = Logger.getInstance(McpServerService::class.java)

    val toolRegistry: ToolRegistry = ToolRegistry()

    // SupervisorJob ensures failure in one tool doesn't cancel others
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val jsonRpcHandler: JsonRpcHandler

    init {
        jsonRpcHandler = JsonRpcHandler(toolRegistry, this)
        toolRegistry.registerBuiltInTools()
        log.info("McpServerService initialized")
    }

    fun getJsonRpcHandler(): JsonRpcHandler = jsonRpcHandler

    fun getServerPort(): Int {
        return BuiltInServerManager.getInstance().port
    }

    fun getServerUrl(): String {
        val port = getServerPort()
        return "http://localhost:$port${McpConstants.SSE_ENDPOINT_PATH}"
    }

    fun getPostEndpointUrl(): String {
        val port = getServerPort()
        return "http://localhost:$port${McpConstants.MCP_ENDPOINT_PATH}"
    }

    fun getServerInfo(): ServerInfo = ServerInfo(
        name = McpConstants.SERVER_NAME,
        version = McpConstants.SERVER_VERSION
    )

    fun isServerRunning(): Boolean {
        return try {
            BuiltInServerManager.getInstance().port > 0
        } catch (e: Exception) {
            false
        }
    }

    override fun dispose() {
        log.info("McpServerService disposing")
        coroutineScope.cancel("McpServerService disposed")
    }

    companion object {
        @JvmStatic
        fun getInstance(): McpServerService = service()
    }
}
