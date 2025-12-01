# IntelliJ Debugger MCP Plugin - Design Document

**Document Version**: 1.0
**Status**: Draft
**Based on**: requirements.md v1.0

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Structure](#2-package-structure)
3. [Core Components](#3-core-components)
4. [Data Models](#4-data-models)
5. [MCP Server Implementation](#5-mcp-server-implementation)
6. [Tool Implementation](#6-tool-implementation)
7. [GUI Components](#7-gui-components)
8. [Settings & Configuration](#8-settings--configuration)
9. [Threading Strategy](#9-threading-strategy)
10. [Error Handling](#10-error-handling)
11. [Testing Strategy](#11-testing-strategy)

---

## 1. Architecture Overview

### 1.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AI Coding Agents                                   │
│              (Claude Code, Cursor, Windsurf, VS Code, etc.)                 │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
                                  │ HTTP+SSE Transport
                                  │ GET  /debugger-mcp/sse → SSE stream
                                  │ POST /debugger-mcp     → JSON-RPC requests
                                  │
┌─────────────────────────────────▼───────────────────────────────────────────┐
│                         IntelliJ IDEA Instance                               │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │              IDE Built-in Web Server (port 63342, etc.)               │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  /api/mcp/*       → Built-in JetBrains MCP (2025.2+)            │  │  │
│  │  │  /debugger-mcp/*  → Our Debugger MCP Plugin                     │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                  │                                           │
│  ┌───────────────────────────────▼───────────────────────────────────────┐  │
│  │                    Debugger MCP Plugin Architecture                    │  │
│  │                                                                        │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    Transport Layer                               │  │  │
│  │  │  ┌───────────────────────┐  ┌───────────────────────────────┐  │  │  │
│  │  │  │  McpRequestHandler    │  │     JsonRpcHandler            │  │  │  │
│  │  │  │  (HttpRequestHandler) │  │                               │  │  │  │
│  │  │  └───────────────────────┘  └───────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                     Service Layer                                │  │  │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │  │  │
│  │  │  │McpServer    │  │ToolRegistry │  │ CommandHistoryService   │  │  │  │
│  │  │  │Service      │  │             │  │                         │  │  │  │
│  │  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                      Tool Layer                                  │  │  │
│  │  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌─────────────┐  │  │  │
│  │  │  │ Session   │  │Breakpoint │  │ Execution │  │ Variable    │  │  │  │
│  │  │  │ Tools     │  │ Tools     │  │ Tools     │  │ Tools       │  │  │  │
│  │  │  └───────────┘  └───────────┘  └───────────┘  └─────────────┘  │  │  │
│  │  │  ┌───────────┐  ┌───────────┐  ┌───────────┐                   │  │  │
│  │  │  │ Stack     │  │Evaluation │  │ Watch     │                   │  │  │
│  │  │  │ Tools     │  │ Tools     │  │ Tools     │                   │  │  │
│  │  │  └───────────┘  └───────────┘  └───────────┘                   │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                      GUI Layer                                   │  │  │
│  │  │  ┌─────────────────────┐  ┌─────────────────────────────────┐  │  │  │
│  │  │  │ McpToolWindowFactory│  │ McpToolWindowPanel              │  │  │  │
│  │  │  └─────────────────────┘  └─────────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                  │                                           │
│  ┌───────────────────────────────▼───────────────────────────────────────┐  │
│  │                    IntelliJ Platform APIs                              │  │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────┐  │  │
│  │  │ XDebuggerManager │  │  XDebugSession   │  │XBreakpointManager  │  │  │
│  │  └──────────────────┘  └──────────────────┘  └────────────────────┘  │  │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────┐  │  │
│  │  │  XStackFrame     │  │XDebuggerEvaluator│  │    RunManager      │  │  │
│  │  └──────────────────┘  └──────────────────┘  └────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Multi-Instance Support

Each IntelliJ IDE instance has its own built-in web server on a unique port:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              User's Machine                                  │
│                                                                              │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │   IntelliJ #1       │  │   IntelliJ #2       │  │   IntelliJ #3       │  │
│  │   Project: backend  │  │   Project: frontend │  │   Project: api      │  │
│  │   Port: 63342       │  │   Port: 63343       │  │   Port: 63344       │  │
│  │   /debugger-mcp     │  │   /debugger-mcp     │  │   /debugger-mcp     │  │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘  │
│                                                                              │
│  Client connects to specific IDE via port:                                   │
│  - http://localhost:63342/debugger-mcp  → IntelliJ #1 (backend)             │
│  - http://localhost:63343/debugger-mcp  → IntelliJ #2 (frontend)            │
│  - http://localhost:63344/debugger-mcp  → IntelliJ #3 (api)                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Multi-Project Resolution

When multiple projects are open in a single IDE instance:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Multi-Project Resolution Flow                         │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Tool Call Request                                 │   │
│  │   {                                                                  │   │
│  │     "name": "set_breakpoint",                                       │   │
│  │     "arguments": {                                                   │   │
│  │       "projectPath": "/path/to/project",  ← OPTIONAL                │   │
│  │       "file_path": "src/Main.java",                                 │   │
│  │       "line": 42                                                    │   │
│  │     }                                                                │   │
│  │   }                                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                  │                                          │
│                                  ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Project Resolution Logic                          │   │
│  │                                                                      │   │
│  │   if (projectPath provided) {                                       │   │
│  │       → Find project matching path                                   │   │
│  │       → Error if not found                                          │   │
│  │   } else if (only 1 project open) {                                 │   │
│  │       → Use that project                                            │   │
│  │   } else {                                                          │   │
│  │       → Return error with available projects list                   │   │
│  │   }                                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                  │                                          │
│                                  ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │         Error Response (when multiple projects, no path)             │   │
│  │   {                                                                  │   │
│  │     "error": "multiple_projects_open",                              │   │
│  │     "message": "Multiple projects are open...",                     │   │
│  │     "open_projects": [                                               │   │
│  │       {"name": "backend", "path": "/Users/dev/backend"},            │   │
│  │       {"name": "frontend", "path": "/Users/dev/frontend"}           │   │
│  │     ]                                                                │   │
│  │   }                                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.4 Component Interaction Flow

```
┌─────────┐      ┌────────────────┐      ┌─────────────┐      ┌──────────────┐
│  Client │─────▶│McpRequestHandler│─────▶│JsonRpcHandler│─────▶│ToolRegistry  │
└─────────┘      └────────────────┘      └─────────────┘      └──────────────┘
                                          │                     │
                                          ▼                     ▼
                                   ┌─────────────┐      ┌──────────────┐
                                   │CommandHistory│◀────│  Tool.execute│
                                   │   Service   │      └──────────────┘
                                   └─────────────┘              │
                                          │                     ▼
                                          ▼              ┌──────────────┐
                                   ┌─────────────┐      │XDebugger APIs│
                                   │    GUI      │      └──────────────┘
                                   │   Update    │
                                   └─────────────┘
```

---

## 2. Package Structure

```
src/main/kotlin/com/github/user/jetbrainsdebuggermcpplugin/
├── McpBundle.kt                           # Resource bundle accessor
├── McpConstants.kt                        # Constants (paths, IDs)
│
├── server/                                # MCP Server components
│   ├── McpServerService.kt                # Main server service (app-level)
│   ├── McpRequestHandler.kt               # HttpRequestHandler for /debugger-mcp
│   ├── JsonRpcHandler.kt                  # JSON-RPC 2.0 message handling
│   └── models/                            # Server data models
│       ├── JsonRpcModels.kt               # JSON-RPC request/response
│       └── McpModels.kt                   # MCP protocol models
│
├── tools/                                 # MCP Tool implementations
│   ├── ToolRegistry.kt                    # Tool registration and lookup
│   ├── McpTool.kt                         # Base tool interface
│   ├── AbstractMcpTool.kt                 # Base implementation
│   │
│   ├── session/                           # Debug session tools
│   │   ├── ListDebugSessionsTool.kt       # list_debug_sessions
│   │   ├── StartDebugSessionTool.kt       # start_debug_session
│   │   ├── StopDebugSessionTool.kt        # stop_debug_session
│   │   └── GetDebugSessionStatusTool.kt   # get_debug_session_status
│   │
│   ├── runconfig/                         # Run configuration tools
│   │   ├── ListRunConfigurationsTool.kt   # list_run_configurations
│   │   └── RunConfigurationTool.kt        # run_configuration
│   │
│   ├── breakpoint/                        # Breakpoint tools
│   │   ├── ListBreakpointsTool.kt         # list_breakpoints
│   │   ├── SetBreakpointTool.kt           # set_breakpoint
│   │   ├── RemoveBreakpointTool.kt        # remove_breakpoint
│   │   └── SetExceptionBreakpointTool.kt  # set_exception_breakpoint
│   │
│   ├── execution/                         # Execution control tools
│   │   ├── ResumeTool.kt                  # resume
│   │   ├── PauseTool.kt                   # pause
│   │   ├── StepOverTool.kt                # step_over
│   │   ├── StepIntoTool.kt                # step_into
│   │   ├── StepOutTool.kt                 # step_out
│   │   └── RunToLineTool.kt               # run_to_line
│   │
│   ├── stack/                             # Stack frame tools
│   │   ├── GetStackTraceTool.kt           # get_stack_trace
│   │   ├── SelectStackFrameTool.kt        # select_stack_frame
│   │   └── ListThreadsTool.kt             # list_threads
│   │
│   ├── variable/                          # Variable inspection tools
│   │   ├── GetVariablesTool.kt            # get_variables
│   │   ├── ExpandVariableTool.kt          # expand_variable
│   │   └── SetVariableTool.kt             # set_variable
│   │
│   ├── evaluation/                        # Expression evaluation tools
│   │   └── EvaluateTool.kt                # evaluate
│   │
│   ├── watch/                             # Watch management tools
│   │   ├── AddWatchTool.kt                # add_watch
│   │   └── RemoveWatchTool.kt             # remove_watch
│   │
│   ├── navigation/                        # Source navigation tools
│   │   └── GetSourceContextTool.kt        # get_source_context
│   │
│   └── models/                            # Tool data models
│       ├── SessionModels.kt               # Session-related models
│       ├── BreakpointModels.kt            # Breakpoint-related models
│       ├── StackModels.kt                 # Stack frame models
│       ├── VariableModels.kt              # Variable models
│       └── EvaluationModels.kt            # Evaluation models
│
├── debugger/                              # Debugger utilities
│   ├── DebuggerService.kt                 # Wrapper for XDebugger APIs
│   ├── SessionManager.kt                  # Debug session tracking
│   ├── BreakpointHelper.kt                # Breakpoint operations
│   ├── EvaluationHelper.kt                # Expression evaluation helpers
│   └── VariableHelper.kt                  # Variable inspection helpers
│
├── history/                               # Command history
│   ├── CommandHistoryService.kt           # History management service
│   └── CommandModels.kt                   # CommandEntry, CommandStatus, etc.
│
├── ui/                                    # GUI components
│   ├── McpToolWindowFactory.kt            # Tool window factory
│   ├── McpToolWindowPanel.kt              # Main panel with all components
│   ├── ServerStatusPanel.kt               # Server status display
│   ├── AgentRuleTipPanel.kt               # Yellow tip panel
│   ├── FilterToolbar.kt                   # Filter/search toolbar
│   └── CommandListCellRenderer.kt         # Custom list renderer
│
├── settings/                              # Plugin settings
│   ├── McpSettings.kt                     # Settings state
│   └── McpSettingsConfigurable.kt         # Settings UI
│
├── util/                                  # Utilities
│   ├── ProjectUtils.kt                    # Project resolution utilities
│   ├── JsonUtils.kt                       # JSON serialization helpers
│   └── ClientConfigGenerator.kt           # Client config generation
│
├── actions/                               # IDE actions
│   ├── CopyServerUrlAction.kt
│   ├── CopyClientConfigAction.kt          # Install on Agents popup
│   ├── ClearHistoryAction.kt
│   ├── ExportHistoryAction.kt
│   └── RefreshAction.kt
│
└── startup/                               # Startup activities
    └── McpServerStartupActivity.kt        # Show notification on startup

src/main/resources/
├── META-INF/
│   ├── plugin.xml                         # Plugin configuration
│   ├── pluginIcon.svg                     # Plugin icon (light)
│   └── pluginIcon_dark.svg                # Plugin icon (dark)
├── messages/
│   └── McpBundle.properties               # i18n messages
└── icons/
    └── toolWindow.svg                     # Tool window icon
```

---

## 3. Core Components

### 3.1 McpServerService

**Responsibility**: Application-level service managing tool registry and coroutine scope.

```kotlin
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val jsonRpcHandler: JsonRpcHandler

    // SupervisorJob ensures failure in one tool doesn't cancel others
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        jsonRpcHandler = JsonRpcHandler(toolRegistry, this)
        toolRegistry.registerBuiltInTools()
    }

    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getJsonRpcHandler(): JsonRpcHandler = jsonRpcHandler

    fun getServerUrl(): String {
        val port = BuiltInServerManager.getInstance().port
        return "http://localhost:$port${McpConstants.MCP_ENDPOINT_PATH}/sse"
    }

    fun getServerInfo(): ServerInfo = ServerInfo(
        name = "jetbrains-debugger",
        version = "1.0.0"
    )

    override fun dispose() {
        coroutineScope.cancel("McpServerService disposed")
    }

    companion object {
        fun getInstance(): McpServerService = service()
    }
}
```

### 3.2 McpRequestHandler

**Responsibility**: Handles HTTP+SSE transport on `/debugger-mcp` and `/debugger-mcp/sse` paths.

```kotlin
class McpRequestHandler : HttpRequestHandler() {

    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith(McpConstants.MCP_ENDPOINT_PATH)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val path = urlDecoder.path()
        val mcpService = McpServerService.getInstance()

        return when {
            // GET /debugger-mcp/sse → SSE stream
            request.method() == HttpMethod.GET && path == McpConstants.SSE_ENDPOINT_PATH -> {
                handleSseRequest(context)
                true
            }
            // POST /debugger-mcp → JSON-RPC request
            request.method() == HttpMethod.POST && path == McpConstants.MCP_ENDPOINT_PATH -> {
                handlePostRequest(request, context, mcpService)
                true
            }
            // OPTIONS for CORS
            request.method() == HttpMethod.OPTIONS -> {
                handleOptionsRequest(context)
                true
            }
            else -> false
        }
    }

    private fun handleSseRequest(context: ChannelHandlerContext) {
        // Send SSE response headers
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, "keep-alive")
            addCorsHeaders(this)
        }
        context.write(response)

        // Send endpoint event with POST URL
        val port = BuiltInServerManager.getInstance().port
        val endpointUrl = "http://localhost:$port${McpConstants.MCP_ENDPOINT_PATH}"
        val endpointEvent = "event: endpoint\ndata: $endpointUrl\n\n"
        val buffer = Unpooled.copiedBuffer(endpointEvent, StandardCharsets.UTF_8)
        context.writeAndFlush(DefaultHttpContent(buffer))
    }

    private fun handlePostRequest(
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        mcpService: McpServerService
    ) {
        val body = request.content().toString(Charsets.UTF_8)

        // Launch on coroutine scope (non-blocking)
        mcpService.coroutineScope.launch {
            val response = mcpService.getJsonRpcHandler().handleRequest(body)
            // Send response back on Netty event loop thread
            context.channel().eventLoop().execute {
                sendJsonResponse(context, HttpResponseStatus.OK, response)
            }
        }
    }

    private fun sendJsonResponse(
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        json: String
    ) {
        val content = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
            set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
            addCorsHeaders(this)
        }
        context.writeAndFlush(response)
    }

    private fun addCorsHeaders(headers: HttpHeaders) {
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Accept")
    }
}
```

### 3.3 JsonRpcHandler

**Responsibility**: Parses JSON-RPC 2.0 messages and routes to appropriate handlers.

```kotlin
class JsonRpcHandler(
    private val toolRegistry: ToolRegistry,
    private val serverService: McpServerService
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun handleRequest(jsonString: String): String {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(jsonString)
        } catch (e: Exception) {
            return json.encodeToString(createParseErrorResponse())
        }

        val response = routeRequest(request)
        return json.encodeToString(response)
    }

    private suspend fun routeRequest(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {
            JsonRpcMethods.INITIALIZE -> processInitialize(request)
            JsonRpcMethods.TOOLS_LIST -> processToolsList(request)
            JsonRpcMethods.TOOLS_CALL -> processToolCall(request)
            else -> createMethodNotFoundResponse(request.id, request.method)
        }
    }

    private suspend fun processToolCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params ?: return createInvalidParamsResponse(request.id)
        val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return createInvalidParamsResponse(request.id)
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createToolNotFoundResponse(request.id, toolName)

        // Resolve project
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.contentOrNull
        val project = ProjectUtils.resolveProject(projectPath)
            ?: return createProjectErrorResponse(request.id, projectPath)

        // Get history service for this project
        val historyService = CommandHistoryService.getInstance(project)

        // Record command start
        val commandEntry = CommandEntry(
            toolName = toolName,
            parameters = arguments
        )
        historyService.addCommand(commandEntry)

        // Execute tool
        val startTime = System.currentTimeMillis()
        return try {
            val result = tool.execute(project, arguments)
            val duration = System.currentTimeMillis() - startTime

            historyService.updateCommandStatus(
                commandEntry.id,
                if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result.content.firstOrNull()?.let { (it as? ContentBlock.Text)?.text },
                duration
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            historyService.updateCommandStatus(
                commandEntry.id,
                CommandStatus.ERROR,
                e.message,
                duration
            )
            createErrorResponse(request.id, e)
        }
    }
}
```

### 3.4 ToolRegistry

**Responsibility**: Manages registration and lookup of MCP tools.

```kotlin
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun unregister(toolName: String) {
        tools.remove(toolName)
    }

    fun getTool(name: String): McpTool? = tools[name]

    fun getAllTools(): List<McpTool> = tools.values.toList()

    fun getToolDefinitions(): List<ToolDefinition> = tools.values.map { tool ->
        ToolDefinition(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema
        )
    }

    fun registerBuiltInTools() {
        // Run Configuration Tools
        register(ListRunConfigurationsTool())
        register(RunConfigurationTool())

        // Debug Session Tools
        register(ListDebugSessionsTool())
        register(StartDebugSessionTool())
        register(StopDebugSessionTool())
        register(GetDebugSessionStatusTool())

        // Breakpoint Tools
        register(ListBreakpointsTool())
        register(SetBreakpointTool())
        register(RemoveBreakpointTool())
        register(SetExceptionBreakpointTool())

        // Execution Control Tools
        register(ResumeTool())
        register(PauseTool())
        register(StepOverTool())
        register(StepIntoTool())
        register(StepOutTool())
        register(RunToLineTool())

        // Stack Frame Tools
        register(GetStackTraceTool())
        register(SelectStackFrameTool())
        register(ListThreadsTool())

        // Variable Tools
        register(GetVariablesTool())
        register(ExpandVariableTool())
        register(SetVariableTool())

        // Evaluation Tools
        register(EvaluateTool())

        // Watch Tools
        register(AddWatchTool())
        register(RemoveWatchTool())

        // Navigation Tools
        register(GetSourceContextTool())
    }
}
```

### 3.5 CommandHistoryService

**Responsibility**: Records and manages command execution history (project-level).

```kotlin
@Service(Service.Level.PROJECT)
class CommandHistoryService(private val project: Project) {

    private val history = Collections.synchronizedList(mutableListOf<CommandEntry>())
    private val listeners = CopyOnWriteArrayList<CommandHistoryListener>()

    val entries: List<CommandEntry>
        get() = history.toList()

    fun addCommand(entry: CommandEntry) {
        history.add(0, entry)
        trimHistoryIfNeeded()
        notifyListeners { it.onCommandAdded(entry) }
    }

    fun updateCommandStatus(
        id: String,
        status: CommandStatus,
        result: String?,
        durationMs: Long? = null
    ) {
        val entry = history.find { it.id == id } ?: return
        entry.status = status
        entry.result = result
        entry.durationMs = durationMs
        notifyListeners { it.onCommandUpdated(entry) }
    }

    fun clearHistory() {
        history.clear()
        notifyListeners { it.onHistoryCleared() }
    }

    fun getFilteredHistory(filter: CommandFilter): List<CommandEntry> {
        return history.filter { entry ->
            (filter.toolName == null || entry.toolName == filter.toolName) &&
            (filter.status == null || entry.status == filter.status) &&
            (filter.searchText == null || entry.matchesSearch(filter.searchText))
        }
    }

    fun addListener(listener: CommandHistoryListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CommandHistoryListener) {
        listeners.remove(listener)
    }

    private fun trimHistoryIfNeeded() {
        val maxSize = McpSettings.getInstance().maxHistorySize
        while (history.size > maxSize) {
            history.removeAt(history.size - 1)
        }
    }

    private fun notifyListeners(action: (CommandHistoryListener) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach(action)
        }
    }

    companion object {
        fun getInstance(project: Project): CommandHistoryService =
            project.service()
    }
}
```

---

## 4. Data Models

### 4.1 JSON-RPC Models

```kotlin
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
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Custom error codes
    const val NO_DEBUG_SESSION = -32001
    const val SESSION_NOT_FOUND = -32002
    const val BREAKPOINT_ERROR = -32003
    const val EVALUATION_ERROR = -32004
    const val MULTIPLE_PROJECTS = -32005
    const val PROJECT_NOT_FOUND = -32006
}
```

### 4.2 MCP Protocol Models

```kotlin
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
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
    data class Text(val text: String) : ContentBlock()
}
```

### 4.3 Command History Models

```kotlin
data class CommandEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val toolName: String,
    val parameters: JsonObject,
    var status: CommandStatus = CommandStatus.PENDING,
    var result: String? = null,
    var error: String? = null,
    var durationMs: Long? = null
) {
    fun matchesSearch(text: String): Boolean {
        val lowerText = text.lowercase()
        return toolName.lowercase().contains(lowerText) ||
               parameters.toString().lowercase().contains(lowerText) ||
               (result?.lowercase()?.contains(lowerText) == true)
    }
}

enum class CommandStatus {
    PENDING,
    SUCCESS,
    ERROR
}

data class CommandFilter(
    val toolName: String? = null,
    val status: CommandStatus? = null,
    val searchText: String? = null
) {
    fun isEmpty(): Boolean = toolName == null && status == null && searchText == null
}
```

### 4.4 Debugger Models

```kotlin
// Debug Session
@Serializable
data class DebugSessionInfo(
    val id: String,
    val name: String,
    val state: String,  // "running", "paused", "stopped"
    val isCurrent: Boolean,
    val runConfigurationName: String?,
    val processId: Long?
)

// Rich Debug Status (get_debug_session_status response)
@Serializable
data class DebugSessionStatus(
    val sessionId: String,
    val name: String,
    val state: String,
    val pausedReason: String?,  // "breakpoint", "step", "pause", "exception"
    val currentLocation: SourceLocation?,
    val breakpointHit: BreakpointHitInfo?,
    val stackSummary: List<StackFrameInfo>,
    val totalStackDepth: Int,
    val variables: List<VariableInfo>,
    val watches: List<WatchInfo>,
    val sourceContext: SourceContext?,
    val currentThread: ThreadInfo?,
    val threadCount: Int
)

// Stack Frame
@Serializable
data class StackFrameInfo(
    val index: Int,
    val file: String?,
    val line: Int?,
    val className: String?,
    val methodName: String?,
    val isCurrent: Boolean = false,
    val isLibrary: Boolean = false
)

// Variable
@Serializable
data class VariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val hasChildren: Boolean,
    val id: String? = null  // For expanding
)

// Watch
@Serializable
data class WatchInfo(
    val id: String,
    val expression: String,
    val value: String?,
    val type: String?,
    val error: String?
)

// Breakpoint
@Serializable
data class BreakpointInfo(
    val id: String,
    val type: String,  // "line", "exception", "method", "field"
    val file: String?,
    val line: Int?,
    val enabled: Boolean,
    val condition: String?,
    val logMessage: String?,
    val suspendPolicy: String?,
    val hitCount: Int,
    val temporary: Boolean,
    // For exception breakpoints
    val exceptionClass: String?,
    val caught: Boolean?,
    val uncaught: Boolean?
)

// Source Context
@Serializable
data class SourceContext(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val currentLine: Int,
    val lines: List<SourceLine>,
    val breakpointsInView: List<Int>
)

@Serializable
data class SourceLine(
    val number: Int,
    val content: String,
    val isCurrent: Boolean = false
)

// Source Location
@Serializable
data class SourceLocation(
    val file: String,
    val line: Int,
    val className: String?,
    val methodName: String?,
    val signature: String?
)

// Thread
@Serializable
data class ThreadInfo(
    val id: String,
    val name: String,
    val state: String,  // "running", "paused", "waiting"
    val isCurrent: Boolean = false,
    val group: String? = null
)

// Run Configuration
@Serializable
data class RunConfigurationInfo(
    val name: String,
    val type: String,
    val typeId: String,
    val isTemporary: Boolean,
    val canRun: Boolean,
    val canDebug: Boolean,
    val folder: String?,
    val description: String?
)

// Evaluation Result
@Serializable
data class EvaluationResult(
    val value: String,
    val type: String,
    val hasChildren: Boolean,
    val id: String?  // For expanding
)
```

---

## 5. MCP Server Implementation

### 5.1 HTTP+SSE Endpoint Design

**SSE Endpoint**: `http://localhost:{IDE_PORT}/debugger-mcp/sse`

| Method | Purpose | Response |
|--------|---------|----------|
| GET | Establish SSE stream | SSE stream with `endpoint` event |

**JSON-RPC Endpoint**: `http://localhost:{IDE_PORT}/debugger-mcp`

| Method | Purpose | Request Body | Response |
|--------|---------|--------------|----------|
| POST | JSON-RPC requests | JSON-RPC Request | JSON-RPC Response |

**Connection Flow:**
1. Client opens GET to `/debugger-mcp/sse`
2. Server sends: `event: endpoint\ndata: http://localhost:{port}/debugger-mcp\n\n`
3. Client POSTs JSON-RPC to that endpoint
4. Server responds with JSON-RPC response

### 5.2 plugin.xml Registration

```xml
<extensions defaultExtensionNs="com.intellij">
    <httpRequestHandler implementation="...server.McpRequestHandler"/>
</extensions>
```

---

## 6. Tool Implementation

### 6.1 Tool Interface

```kotlin
interface McpTool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult
}
```

### 6.2 Abstract Base Tool

```kotlin
abstract class AbstractMcpTool : McpTool {

    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    protected fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

    protected fun createSuccessResult(text: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = text)),
            isError = false
        )
    }

    protected fun createErrorResult(message: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = message)),
            isError = true
        )
    }

    protected inline fun <reified T> createJsonResult(data: T): ToolCallResult {
        val jsonText = json.encodeToString(data)
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = jsonText)),
            isError = false
        )
    }

    protected fun getDebuggerManager(project: Project): XDebuggerManager {
        return XDebuggerManager.getInstance(project)
    }

    protected fun getCurrentSession(project: Project): XDebugSession? {
        return getDebuggerManager(project).currentSession
    }

    protected fun getSessionById(project: Project, sessionId: String): XDebugSession? {
        return getDebuggerManager(project).debugSessions.find {
            it.hashCode().toString() == sessionId
        }
    }

    protected fun resolveSession(project: Project, sessionId: String?): XDebugSession? {
        return if (sessionId != null) {
            getSessionById(project, sessionId)
        } else {
            getCurrentSession(project)
        }
    }

    companion object {
        fun projectPathProperty(): Pair<String, JsonObject> {
            return "projectPath" to buildJsonObject {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open.")
            }
        }
    }
}
```

### 6.3 Example Tool: GetDebugSessionStatusTool

```kotlin
class GetDebugSessionStatusTool : AbstractMcpTool() {

    override val name = "get_debug_session_status"

    override val description = """
        Get comprehensive status of a debug session including variables, watches,
        stack summary, and source context. This is the primary tool for understanding
        the current debug state in a single call.
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("session_id") {
                put("type", "string")
                put("description", "Debug session ID. Uses current session if omitted.")
            }
            putJsonObject("include_variables") {
                put("type", "boolean")
                put("description", "Include variables from current frame. Default: true")
            }
            putJsonObject("include_source_context") {
                put("type", "boolean")
                put("description", "Include source code around current line. Default: true")
            }
            putJsonObject("source_context_lines") {
                put("type", "integer")
                put("description", "Lines of context above/below current line. Default: 5")
            }
            putJsonObject("max_stack_frames") {
                put("type", "integer")
                put("description", "Maximum stack frames in summary. Default: 5")
            }
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.contentOrNull
        val includeVariables = arguments["include_variables"]?.jsonPrimitive?.booleanOrNull ?: true
        val includeSourceContext = arguments["include_source_context"]?.jsonPrimitive?.booleanOrNull ?: true
        val sourceContextLines = arguments["source_context_lines"]?.jsonPrimitive?.intOrNull ?: 5
        val maxStackFrames = arguments["max_stack_frames"]?.jsonPrimitive?.intOrNull ?: 5

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult("No active debug session")

        val currentFrame = session.currentStackFrame
        val isPaused = session.isPaused

        // Build response
        val status = DebugSessionStatus(
            sessionId = session.hashCode().toString(),
            name = session.sessionName,
            state = if (isPaused) "paused" else "running",
            pausedReason = if (isPaused) determinePauseReason(session) else null,
            currentLocation = currentFrame?.let { getSourceLocation(it) },
            breakpointHit = if (isPaused) getBreakpointHitInfo(session) else null,
            stackSummary = if (isPaused) getStackSummary(session, maxStackFrames) else emptyList(),
            totalStackDepth = if (isPaused) getStackDepth(session) else 0,
            variables = if (isPaused && includeVariables) getVariables(currentFrame) else emptyList(),
            watches = if (isPaused) getWatches(session) else emptyList(),
            sourceContext = if (isPaused && includeSourceContext)
                getSourceContext(project, currentFrame, sourceContextLines) else null,
            currentThread = getCurrentThreadInfo(session),
            threadCount = getThreadCount(session)
        )

        return createJsonResult(status)
    }

    private fun determinePauseReason(session: XDebugSession): String {
        // Check if paused on breakpoint, step, manual pause, or exception
        return "breakpoint" // Simplified - would need more logic
    }

    private fun getSourceLocation(frame: XStackFrame): SourceLocation? {
        val position = frame.sourcePosition ?: return null
        return SourceLocation(
            file = position.file.path,
            line = position.line + 1,
            className = null, // Extract from frame if available
            methodName = null,
            signature = null
        )
    }

    private fun getBreakpointHitInfo(session: XDebugSession): BreakpointHitInfo? {
        // Implementation to get breakpoint that was hit
        return null
    }

    private suspend fun getStackSummary(session: XDebugSession, maxFrames: Int): List<StackFrameInfo> {
        // Get stack frames up to maxFrames
        return emptyList()
    }

    private fun getStackDepth(session: XDebugSession): Int {
        // Get total stack depth
        return 0
    }

    private suspend fun getVariables(frame: XStackFrame?): List<VariableInfo> {
        if (frame == null) return emptyList()
        // Use suspendCancellableCoroutine to convert callback to suspend
        return suspendCancellableCoroutine { continuation ->
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    if (last) {
                        val variables = (0 until children.size()).map { i ->
                            VariableInfo(
                                name = children.getName(i),
                                value = "", // Need to compute presentation
                                type = "",
                                hasChildren = false
                            )
                        }
                        continuation.resume(variables)
                    }
                }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) {
                    continuation.resume(emptyList())
                }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    continuation.resume(emptyList())
                }
                override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
            })
        }
    }

    private fun getWatches(session: XDebugSession): List<WatchInfo> {
        // Get watch expressions and their values
        return emptyList()
    }

    private fun getSourceContext(
        project: Project,
        frame: XStackFrame?,
        contextLines: Int
    ): SourceContext? {
        val position = frame?.sourcePosition ?: return null
        val file = position.file
        val currentLine = position.line + 1

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val startLine = maxOf(1, currentLine - contextLines)
        val endLine = minOf(document.lineCount, currentLine + contextLines)

        val lines = (startLine..endLine).map { lineNum ->
            val lineStart = document.getLineStartOffset(lineNum - 1)
            val lineEnd = document.getLineEndOffset(lineNum - 1)
            val content = document.getText(TextRange(lineStart, lineEnd))
            SourceLine(
                number = lineNum,
                content = content,
                isCurrent = lineNum == currentLine
            )
        }

        return SourceContext(
            file = file.path,
            startLine = startLine,
            endLine = endLine,
            currentLine = currentLine,
            lines = lines,
            breakpointsInView = emptyList() // Would need to check breakpoint manager
        )
    }

    private fun getCurrentThreadInfo(session: XDebugSession): ThreadInfo? {
        // Get current thread info
        return null
    }

    private fun getThreadCount(session: XDebugSession): Int {
        // Get total thread count
        return 1
    }
}
```

### 6.4 Example Tool: SetBreakpointTool

```kotlin
class SetBreakpointTool : AbstractMcpTool() {

    override val name = "set_breakpoint"

    override val description = """
        Add a line breakpoint at a specified location. Supports conditions,
        log messages, and suspend policies.
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the file")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
                put("minimum", 1)
            }
            putJsonObject("condition") {
                put("type", "string")
                put("description", "Conditional expression (breakpoint only hits when true)")
            }
            putJsonObject("log_message") {
                put("type", "string")
                put("description", "Log message (tracepoint). Use {expr} for expression evaluation.")
            }
            putJsonObject("suspend_policy") {
                put("type", "string")
                put("enum", listOf("all", "thread", "none"))
                put("description", "Thread suspend policy. Default: all")
            }
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "Whether breakpoint is enabled. Default: true")
            }
            putJsonObject("temporary") {
                put("type", "boolean")
                put("description", "Remove after first hit. Default: false")
            }
        }
        putJsonArray("required") {
            add("file_path")
            add("line")
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments["file_path"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file_path")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val condition = arguments["condition"]?.jsonPrimitive?.contentOrNull
        val logMessage = arguments["log_message"]?.jsonPrimitive?.contentOrNull
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val temporary = arguments["temporary"]?.jsonPrimitive?.booleanOrNull ?: false

        // Find the file
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return createErrorResult("File not found: $filePath")

        // Get breakpoint manager
        val breakpointManager = getDebuggerManager(project).breakpointManager

        // Create breakpoint (must be on EDT for write action)
        return withContext(Dispatchers.EDT) {
            ApplicationManager.getApplication().runWriteAction<ToolCallResult> {
                try {
                    // Find the line breakpoint type
                    val lineBreakpointType = XBreakpointType.EXTENSION_POINT_NAME
                        .extensionList
                        .filterIsInstance<XLineBreakpointType<*>>()
                        .firstOrNull { it.canPutAt(virtualFile, line - 1, project) }
                        ?: return@runWriteAction createErrorResult(
                            "Cannot set breakpoint at $filePath:$line"
                        )

                    val breakpoint = breakpointManager.addLineBreakpoint(
                        lineBreakpointType as XLineBreakpointType<XBreakpointProperties<*>>,
                        virtualFile.url,
                        line - 1,  // 0-based
                        null,
                        temporary
                    )

                    if (breakpoint == null) {
                        return@runWriteAction createErrorResult(
                            "Failed to create breakpoint at $filePath:$line"
                        )
                    }

                    // Configure breakpoint
                    breakpoint.isEnabled = enabled
                    condition?.let { breakpoint.conditionExpression = XExpressionImpl.fromText(it) }
                    logMessage?.let { breakpoint.logExpressionObject = XExpressionImpl.fromText(it) }

                    createJsonResult(mapOf(
                        "breakpoint_id" to breakpoint.hashCode().toString(),
                        "status" to "set",
                        "verified" to true,
                        "message" to "Breakpoint set at ${virtualFile.name}:$line"
                    ))
                } catch (e: Exception) {
                    createErrorResult("Failed to set breakpoint: ${e.message}")
                }
            }
        }
    }
}
```

---

## 7. GUI Components

### 7.1 Tool Window Structure

```
McpToolWindowFactory
    └── McpToolWindowPanel
            ├── [Toolbar]
            │       ├── Refresh | Copy URL | Clear | Export
            │       └── [Install on Agents ▼] (right side)
            │
            ├── ServerStatusPanel
            │       ├── StatusLabel (● MCP Server Running)
            │       ├── UrlLabel (http://localhost:63342/...)
            │       └── ProjectLabel (| Project: myapp)
            │
            ├── AgentRuleTipPanel (Yellow background)
            │       ├── InfoIcon
            │       ├── TipLabel
            │       └── CopyRuleLink
            │
            ├── FilterToolbar
            │       ├── ToolNameFilter (ComboBox)
            │       ├── StatusFilter (ComboBox)
            │       └── SearchField
            │
            └── JBSplitter (60/40)
                    ├── CommandHistoryList (Top)
                    │       └── CommandListCellRenderer
                    │               ├── Timestamp (gray)
                    │               ├── ToolName (bold)
                    │               └── Status (colored)
                    │
                    └── DetailsArea (Bottom)
                            └── Monospace text with:
                                - Tool name
                                - Status
                                - Duration
                                - Parameters (JSON)
                                - Result/Error
```

### 7.2 McpToolWindowFactory

```kotlin
class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)

        // Create toolbar
        val actionManager = ActionManager.getInstance()
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            add(CopyServerUrlAction())
            addSeparator()
            add(ClearHistoryAction())
            add(ExportHistoryAction())
        }

        val toolbar = actionManager.createActionToolbar(
            "DebuggerMcpToolbar",
            actionGroup,
            true
        )
        toolbar.targetComponent = panel

        // Create Install button (right side)
        val installButton = JButton("Install on Agents").apply {
            icon = AllIcons.FileTypes.Config
            addActionListener {
                val action = CopyClientConfigAction()
                val event = AnActionEvent.createFromDataContext(
                    "DebuggerMcpInstall",
                    null,
                    DataManager.getInstance().getDataContext(this)
                )
                action.actionPerformed(event)
            }
        }

        // Layout toolbar
        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(installButton, BorderLayout.EAST)
        }

        // Main wrapper
        val wrapper = JPanel(BorderLayout()).apply {
            add(toolbarPanel, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(wrapper, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
```

### 7.3 McpToolWindowPanel

```kotlin
class McpToolWindowPanel(
    private val project: Project
) : JBPanel<McpToolWindowPanel>(BorderLayout()), Disposable, CommandHistoryListener {

    private val serverStatusPanel: ServerStatusPanel
    private val agentRuleTipPanel: AgentRuleTipPanel
    private val filterToolbar: FilterToolbar
    private val historyListModel = DefaultListModel<CommandEntry>()
    private val historyList: JBList<CommandEntry>
    private val detailsArea: JBTextArea
    private val historyService: CommandHistoryService
    private var currentFilter = CommandFilter()

    init {
        // Header panel
        val headerPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        serverStatusPanel = ServerStatusPanel(project)
        headerPanel.add(serverStatusPanel)

        agentRuleTipPanel = AgentRuleTipPanel(project)
        headerPanel.add(agentRuleTipPanel)

        filterToolbar = FilterToolbar { filter ->
            currentFilter = filter
            refreshHistory()
        }
        headerPanel.add(filterToolbar)

        add(headerPanel, BorderLayout.NORTH)

        // History list
        historyList = JBList(historyListModel).apply {
            cellRenderer = CommandListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedValue?.let { showCommandDetails(it) }
                }
            }
        }

        // Details area
        detailsArea = JBTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }

        // Splitter
        val splitter = JBSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(historyList)
            secondComponent = JBScrollPane(detailsArea)
        }

        add(splitter, BorderLayout.CENTER)

        // Register listener
        historyService = CommandHistoryService.getInstance(project)
        historyService.addListener(this)
        refreshHistory()
    }

    fun refresh() {
        serverStatusPanel.refresh()
        refreshHistory()
    }

    private fun refreshHistory() {
        historyListModel.clear()
        val entries = if (currentFilter.isEmpty()) {
            historyService.entries
        } else {
            historyService.getFilteredHistory(currentFilter)
        }
        entries.forEach { historyListModel.addElement(it) }
    }

    private fun showCommandDetails(entry: CommandEntry) {
        val sb = StringBuilder()
        sb.appendLine("Tool: ${entry.toolName}")
        sb.appendLine("Status: ${entry.status}")
        sb.appendLine("Timestamp: ${entry.timestamp}")
        entry.durationMs?.let { sb.appendLine("Duration: ${it}ms") }
        sb.appendLine()
        sb.appendLine("Parameters:")
        sb.appendLine(entry.parameters.toString())

        if (entry.error != null) {
            sb.appendLine()
            sb.appendLine("Error:")
            sb.appendLine(entry.error)
        }

        if (entry.result != null) {
            sb.appendLine()
            sb.appendLine("Result:")
            sb.appendLine(entry.result)
        }

        detailsArea.text = sb.toString()
        detailsArea.caretPosition = 0
    }

    override fun onCommandAdded(entry: CommandEntry) {
        historyListModel.add(0, entry)
        if (McpSettings.getInstance().autoScroll) {
            historyList.selectedIndex = 0
        }
    }

    override fun onCommandUpdated(entry: CommandEntry) {
        val index = (0 until historyListModel.size).firstOrNull {
            historyListModel.getElementAt(it).id == entry.id
        }
        index?.let {
            historyListModel.setElementAt(entry, it)
            if (historyList.selectedIndex == it) {
                showCommandDetails(entry)
            }
        }
    }

    override fun onHistoryCleared() {
        historyListModel.clear()
        detailsArea.text = ""
    }

    override fun dispose() {
        historyService.removeListener(this)
    }
}
```

### 7.4 AgentRuleTipPanel

```kotlin
class AgentRuleTipPanel(private val project: Project) : JBPanel<AgentRuleTipPanel>(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    companion object {
        const val AGENT_RULE_TEXT = "IMPORTANT: When debugging, prefer using jetbrains-debugger MCP tools to interact with the IDE debugger."

        val CONFIG_FILES_HINT = """
            Add this rule to your AI agent's configuration file:
            • Claude Code: CLAUDE.md (project root) or ~/.claude/CLAUDE.md (global)
            • Cursor: .cursorrules or .cursor/rules/*.mdc
            • Other agents: Check your agent's documentation
        """.trimIndent()
    }

    init {
        border = JBUI.Borders.empty(2, 8)
        background = JBColor(0xFFFBE6, 0x3D3D00)  // Yellow

        val iconLabel = JBLabel(AllIcons.General.BalloonInformation)

        val tipLabel = JBLabel("Tip: Copy this rule to your CLAUDE.md/AGENTS.md/.cursorrules file").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        val copyLink = JBLabel("Copy rule").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    copyAgentRule()
                }

                override fun mouseEntered(e: MouseEvent) {
                    text = "<html><u>Copy rule</u></html>"
                }

                override fun mouseExited(e: MouseEvent) {
                    text = "Copy rule"
                }
            })
        }

        add(iconLabel)
        add(tipLabel)
        add(copyLink)
    }

    private fun copyAgentRule() {
        CopyPasteManager.getInstance().setContents(StringSelection(AGENT_RULE_TEXT))

        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(
                "Rule copied to clipboard",
                "$AGENT_RULE_TEXT\n\n$CONFIG_FILES_HINT",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
```

### 7.5 CopyClientConfigAction

```kotlin
class CopyClientConfigAction : AnAction() {

    init {
        templatePresentation.text = "Install on Coding Agents"
        templatePresentation.icon = AllIcons.FileTypes.Config
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val popup = createInstallPopup(project)
        popup.showInBestPositionFor(e.dataContext)
    }

    private fun createInstallPopup(project: Project?) = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(createPopupContent(project), null)
        .setTitle("Install on Coding Agents")
        .setMovable(false)
        .setResizable(false)
        .setRequestFocus(true)
        .createPopup()

    private fun createPopupContent(project: Project?): JPanel {
        val mainPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }

        // Install Now section
        mainPanel.add(createSectionHeader("Install Now"))
        mainPanel.add(createInstallNowList(project))

        // Separator
        mainPanel.add(createSeparator())

        // Copy Configuration section
        mainPanel.add(createSectionHeader("Copy Configuration"))
        mainPanel.add(createCopyConfigList(project))

        return mainPanel
    }

    private fun createSectionHeader(title: String): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = JBColor.GRAY
            }, BorderLayout.WEST)
        }
    }

    private fun createSeparator(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel().apply {
                preferredSize = JBUI.size(0, 1)
                background = JBColor(Gray._220, Gray._60)
            }, BorderLayout.CENTER)
        }
    }

    private fun createInstallNowList(project: Project?): JPanel {
        // Similar to index plugin - list with Claude Code CLI option
        // Runs: claude mcp remove jetbrains-debugger ; claude mcp add --transport http jetbrains-debugger {url}
        return JPanel()
    }

    private fun createCopyConfigList(project: Project?): JPanel {
        // List of clients: Claude Desktop, Cursor, VS Code, Windsurf
        // Each copies appropriate JSON config to clipboard
        return JPanel()
    }
}
```

---

## 8. Settings & Configuration

### 8.1 McpSettings

```kotlin
@Service(Service.Level.APP)
@State(
    name = "DebuggerMcpSettings",
    storages = [Storage("debugger-mcp-settings.xml")]
)
class McpSettings : PersistentStateComponent<McpSettings.State> {

    data class State(
        var maxHistorySize: Int = 100,
        var autoScroll: Boolean = true
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var maxHistorySize: Int
        get() = state.maxHistorySize
        set(value) { state.maxHistorySize = value }

    var autoScroll: Boolean
        get() = state.autoScroll
        set(value) { state.autoScroll = value }

    companion object {
        fun getInstance(): McpSettings = service()
    }
}
```

### 8.2 plugin.xml Configuration

```xml
<idea-plugin>
    <id>com.github.user.jetbrainsdebuggermcpplugin</id>
    <name>Debugger MCP Server</name>
    <vendor>user</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.xdebugger</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Application Service -->
        <applicationService
            serviceImplementation="...server.McpServerService"/>

        <!-- Project Service -->
        <projectService
            serviceImplementation="...history.CommandHistoryService"/>

        <!-- HTTP Request Handler -->
        <httpRequestHandler
            implementation="...server.McpRequestHandler"/>

        <!-- Tool Window -->
        <toolWindow
            id="Debugger MCP Server"
            anchor="bottom"
            icon="...icons.McpIcons.ToolWindow"
            factoryClass="...ui.McpToolWindowFactory"/>

        <!-- Settings -->
        <applicationService
            serviceImplementation="...settings.McpSettings"/>
        <applicationConfigurable
            id="debugger.mcp.settings"
            displayName="Debugger MCP Server"
            instance="...settings.McpSettingsConfigurable"/>

        <!-- Startup Activity -->
        <postStartupActivity
            implementation="...startup.McpServerStartupActivity"/>

        <!-- Notifications -->
        <notificationGroup
            id="Debugger MCP Server"
            displayType="BALLOON"/>
    </extensions>

    <actions>
        <group id="DebuggerMcp.Actions" text="Debugger MCP Server">
            <action id="DebuggerMcp.CopyUrl"
                class="...actions.CopyServerUrlAction"
                text="Copy Server URL"/>
            <action id="DebuggerMcp.CopyConfig"
                class="...actions.CopyClientConfigAction"
                text="Install on Coding Agents"/>
            <action id="DebuggerMcp.ClearHistory"
                class="...actions.ClearHistoryAction"
                text="Clear History"/>
            <action id="DebuggerMcp.ExportHistory"
                class="...actions.ExportHistoryAction"
                text="Export History..."/>
        </group>
    </actions>
</idea-plugin>
```

---

## 9. Threading Strategy

### 9.1 Thread Usage Matrix

| Operation | Thread | Approach |
|-----------|--------|----------|
| HTTP Request Handling | Netty IO | Non-blocking |
| Tool Execution | Coroutine (Default) | `suspend fun` |
| XDebugger API calls | Varies | Check API docs |
| Breakpoint Creation | EDT | `runWriteAction` |
| GUI Update | EDT | `invokeLater` |
| History Update | Any + EDT notify | Service + listener |
| Async Debugger Callbacks | Callback thread | Convert to coroutine |

### 9.2 Coroutine Scope Management

```kotlin
// In McpServerService
val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// SupervisorJob ensures:
// - One tool failure doesn't cancel others
// - Scope is cancelled on service dispose
```

### 9.3 Converting Async Debugger Callbacks

```kotlin
// XDebugger uses callbacks - convert to suspend
suspend fun <T> suspendCallback(block: (callback: (T) -> Unit) -> Unit): T {
    return suspendCancellableCoroutine { continuation ->
        block { result ->
            continuation.resume(result)
        }
    }
}

// Example: Getting variables
suspend fun getVariables(frame: XStackFrame): List<VariableInfo> {
    return suspendCancellableCoroutine { continuation ->
        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                if (last) {
                    val result = buildVariableList(children)
                    continuation.resume(result)
                }
            }
            // ... other methods
        })
    }
}
```

---

## 10. Error Handling

### 10.1 Exception Hierarchy

```kotlin
sealed class McpException(
    message: String,
    val errorCode: Int
) : Exception(message)

class NoDebugSessionException(message: String = "No active debug session") :
    McpException(message, JsonRpcErrorCodes.NO_DEBUG_SESSION)

class SessionNotFoundException(sessionId: String) :
    McpException("Debug session not found: $sessionId", JsonRpcErrorCodes.SESSION_NOT_FOUND)

class BreakpointException(message: String) :
    McpException(message, JsonRpcErrorCodes.BREAKPOINT_ERROR)

class EvaluationException(message: String) :
    McpException(message, JsonRpcErrorCodes.EVALUATION_ERROR)

class MultipleProjectsException(projects: List<ProjectInfo>) :
    McpException(
        "Multiple projects are open. Please specify 'projectPath' parameter.",
        JsonRpcErrorCodes.MULTIPLE_PROJECTS
    )

class ProjectNotFoundException(path: String) :
    McpException("Project not found: $path", JsonRpcErrorCodes.PROJECT_NOT_FOUND)
```

### 10.2 Error Response Builder

```kotlin
object ErrorResponseBuilder {
    fun fromException(id: JsonElement?, e: Exception): JsonRpcResponse {
        return when (e) {
            is McpException -> JsonRpcResponse(
                id = id,
                error = JsonRpcError(
                    code = e.errorCode,
                    message = e.message ?: "Unknown error"
                )
            )
            else -> JsonRpcResponse(
                id = id,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
            )
        }
    }
}
```

---

## 11. Testing Strategy

### 11.1 Test Categories

| Category | Framework | Coverage |
|----------|-----------|----------|
| Unit Tests | JUnit 5 + MockK | Tools, Services, Utilities |
| Integration Tests | IntelliJ Test Framework | Debugger operations |
| UI Tests | IntelliJ UI Test Robot | Tool window, Settings |

### 11.2 Test Structure

```
src/test/kotlin/
├── server/
│   ├── McpServerServiceTest.kt
│   ├── JsonRpcHandlerTest.kt
│   └── McpRequestHandlerTest.kt
│
├── tools/
│   ├── session/
│   │   └── GetDebugSessionStatusToolTest.kt
│   ├── breakpoint/
│   │   └── SetBreakpointToolTest.kt
│   └── execution/
│       └── StepOverToolTest.kt
│
├── history/
│   └── CommandHistoryServiceTest.kt
│
├── util/
│   ├── ProjectUtilsTest.kt
│   └── ClientConfigGeneratorTest.kt
│
└── integration/
    └── DebuggerMcpIntegrationTest.kt
```

### 11.3 Example Test

```kotlin
class SetBreakpointToolTest : BasePlatformTestCase() {

    private lateinit var tool: SetBreakpointTool

    override fun setUp() {
        super.setUp()
        tool = SetBreakpointTool()
    }

    fun `test set breakpoint returns success`() {
        // Given
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                public void foo() {
                    int x = 1;  // line 3
                }
            }
            """.trimIndent()
        )

        // When
        val result = runBlocking {
            tool.execute(
                project,
                buildJsonObject {
                    put("file_path", myFixture.file.virtualFile.path)
                    put("line", 3)
                }
            )
        }

        // Then
        assertFalse(result.isError)
        val text = (result.content.first() as ContentBlock.Text).text
        assertTrue(text.contains("breakpoint_id"))
        assertTrue(text.contains("\"status\":\"set\""))
    }

    fun `test set breakpoint with invalid line returns error`() {
        // Given
        myFixture.configureByText("Test.java", "public class Test {}")

        // When
        val result = runBlocking {
            tool.execute(
                project,
                buildJsonObject {
                    put("file_path", myFixture.file.virtualFile.path)
                    put("line", 999)
                }
            )
        }

        // Then
        assertTrue(result.isError)
    }
}
```

---

## Appendix A: Tool Summary

| Tool Name | Category | Priority |
|-----------|----------|----------|
| `list_run_configurations` | Run Configs | P0 |
| `run_configuration` | Run Configs | P0 |
| `list_debug_sessions` | Debug Session | P0 |
| `start_debug_session` | Debug Session | P0 |
| `stop_debug_session` | Debug Session | P0 |
| `get_debug_session_status` | Debug Session | P0 |
| `list_breakpoints` | Breakpoints | P0 |
| `set_breakpoint` | Breakpoints | P0 |
| `remove_breakpoint` | Breakpoints | P0 |
| `set_exception_breakpoint` | Breakpoints | P2 |
| `resume` | Execution | P0 |
| `pause` | Execution | P0 |
| `step_over` | Execution | P0 |
| `step_into` | Execution | P0 |
| `step_out` | Execution | P1 |
| `run_to_line` | Execution | P1 |
| `get_stack_trace` | Stack | P0 |
| `select_stack_frame` | Stack | P1 |
| `list_threads` | Stack | P1 |
| `get_variables` | Variables | P0 |
| `expand_variable` | Variables | P1 |
| `set_variable` | Variables | P2 |
| `evaluate` | Evaluation | P0 |
| `add_watch` | Watches | P2 |
| `remove_watch` | Watches | P2 |
| `get_source_context` | Navigation | P1 |

**Total Tools: 26**

---

## Appendix B: Dependencies

### Important: Bundled Libraries

The IntelliJ Platform SDK **bundles** certain libraries that plugins must NOT add separately:
- **Kotlin Coroutines** - bundled, do NOT add as dependency
- **Kotlin Standard Library** - bundled, set `kotlin.stdlib.default.dependency = false`

See: https://plugins.jetbrains.com/docs/intellij/kotlin-coroutines.html

### build.gradle.kts additions

```kotlin
plugins {
    // ... other plugins
    alias(libs.plugins.kotlinSerialization) // Required for @Serializable
}

dependencies {
    // Kotlinx Serialization - NOT bundled, must add
    implementation(libs.kotlinx.serialization.json)

    // DO NOT add kotlinx-coroutines - use IntelliJ's bundled version!

    // Testing - exclude coroutines from mockk
    testImplementation(libs.mockk) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
}
```

### gradle/libs.versions.toml

```toml
[versions]
kotlinxSerializationJson = "1.7.3"
mockk = "1.14.6"

[libraries]
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }

[plugins]
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

### gradle.properties additions

```properties
# Use bundled Kotlin stdlib
kotlin.stdlib.default.dependency = false

# Bundled plugins for debugger support
platformBundledPlugins = com.intellij.java
```

---

## Appendix C: Document History

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | 2025-12-01 | Initial design document |
