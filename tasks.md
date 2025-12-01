# Implementation Tasks

**Project**: JetBrains Debugger MCP Plugin
**Based on**: design.md v1.0
**Status**: Planning
**Last Updated**: 2025-12-01

---

## Progress Overview

| Phase | Description | Status | Progress |
|-------|-------------|--------|----------|
| 1 | Project Setup & Foundation | Not Started | 0/8 |
| 2 | Server Infrastructure | Not Started | 0/12 |
| 3 | Tool Framework | Not Started | 0/10 |
| 4 | P0 Tools - Core Debugging | Not Started | 0/24 |
| 5 | Command History Service | Not Started | 0/8 |
| 6 | GUI - Tool Window | Not Started | 0/16 |
| 7 | P1 Tools - Enhanced Features | Not Started | 0/12 |
| 8 | P2 Tools - Advanced Features | Not Started | 0/8 |
| 9 | Settings & Actions | Not Started | 0/14 |
| 10 | Testing & Polish | Not Started | 0/12 |

**Total Tasks**: 124
**Completed**: 0
**Overall Progress**: 0%

---

## Phase 1: Project Setup & Foundation

**Goal**: Configure build system, create package structure, and set up foundational utilities.

**Prerequisites**: None

### Tasks

- [ ] **1.1** Update `build.gradle.kts` with required dependencies
  - [ ] Add kotlinx-serialization-json dependency (NOT bundled with SDK)
  - [ ] Configure serialization plugin
  - [ ] Add test dependencies (mockk with coroutines exclusions)
  - [ ] **NOTE**: Do NOT add kotlinx-coroutines - use IntelliJ's bundled version
  - [ ] If using external libs that depend on coroutines, exclude them:
    ```kotlin
    implementation(someLib) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
    ```

- [ ] **1.2** Update `gradle.properties`
  - [ ] Add `platformBundledPlugins = com.intellij.java` for Java debugger support
  - [ ] Set `kotlin.stdlib.default.dependency = false` (use bundled stdlib)

- [ ] **1.3** Create package structure
  ```
  src/main/kotlin/com/github/user/jetbrainsdebuggermcpplugin/
  ├── server/
  │   └── models/
  ├── tools/
  │   ├── session/
  │   ├── runconfig/
  │   ├── breakpoint/
  │   ├── execution/
  │   ├── stack/
  │   ├── variable/
  │   ├── evaluation/
  │   ├── watch/
  │   ├── navigation/
  │   └── models/
  ├── debugger/
  ├── history/
  ├── ui/
  ├── settings/
  ├── util/
  ├── actions/
  └── startup/
  ```

- [ ] **1.4** Create `McpConstants.kt`
  - [ ] Define MCP_ENDPOINT_PATH = "/debugger-mcp"
  - [ ] Define SSE_ENDPOINT_PATH = "/debugger-mcp/sse"
  - [ ] Define NOTIFICATION_GROUP_ID
  - [ ] Define SERVER_NAME = "jetbrains-debugger"
  - [ ] Define SERVER_VERSION = "1.0.0"

- [ ] **1.5** Create `McpBundle.kt` for i18n (optional, can defer)

- [ ] **1.6** Create `util/JsonUtils.kt`
  - [ ] Configure Json serializer with appropriate settings
  - [ ] Add extension functions for common operations

- [ ] **1.7** Create `util/ProjectUtils.kt`
  - [ ] Implement `resolveProject(projectPath: String?): Project?`
  - [ ] Implement `getOpenProjects(): List<ProjectInfo>`
  - [ ] Handle single project (auto-select) case
  - [ ] Handle multiple projects (require path) case

- [ ] **1.8** Verify project builds successfully
  - [ ] Run `./gradlew build`
  - [ ] Ensure no compilation errors

**Phase 1 Deliverables**:
- Configured build system
- Complete package structure
- Core utility classes
- Building project

---

## Phase 2: Server Infrastructure

**Goal**: Implement MCP server with HTTP+SSE transport, JSON-RPC handling, and request routing.

**Prerequisites**: Phase 1 complete

### Tasks

- [ ] **2.1** Create `server/models/JsonRpcModels.kt`
  - [ ] Define `JsonRpcRequest` data class
  - [ ] Define `JsonRpcResponse` data class
  - [ ] Define `JsonRpcError` data class
  - [ ] Define `JsonRpcErrorCodes` object with standard and custom codes
  - [ ] Define `JsonRpcMethods` object with method constants

- [ ] **2.2** Create `server/models/McpModels.kt`
  - [ ] Define `ToolDefinition` data class
  - [ ] Define `ToolCallResult` data class
  - [ ] Define `ContentBlock` sealed class with Text subclass
  - [ ] Define `ServerInfo` data class
  - [ ] Define `InitializeResult` data class

- [ ] **2.3** Create `server/McpServerService.kt`
  - [ ] Annotate as `@Service(Service.Level.APP)`
  - [ ] Create coroutineScope with SupervisorJob + Dispatchers.Default
  - [ ] Implement `getServerUrl(): String`
  - [ ] Implement `getServerInfo(): ServerInfo`
  - [ ] Implement Disposable interface
  - [ ] Add companion object with `getInstance()`

- [ ] **2.4** Create `server/JsonRpcHandler.kt`
  - [ ] Inject ToolRegistry dependency
  - [ ] Implement `suspend fun handleRequest(jsonString: String): String`
  - [ ] Implement request parsing with error handling
  - [ ] Implement method routing (initialize, tools/list, tools/call)

- [ ] **2.5** Implement `processInitialize` in JsonRpcHandler
  - [ ] Return server info and capabilities
  - [ ] Include tools capability

- [ ] **2.6** Implement `processToolsList` in JsonRpcHandler
  - [ ] Get tool definitions from registry
  - [ ] Format as MCP tools/list response

- [ ] **2.7** Implement `processToolCall` in JsonRpcHandler
  - [ ] Extract tool name and arguments
  - [ ] Resolve project from arguments
  - [ ] Execute tool
  - [ ] Handle errors with appropriate error codes
  - [ ] Return formatted result

- [ ] **2.8** Create `server/McpRequestHandler.kt`
  - [ ] Extend `HttpRequestHandler`
  - [ ] Implement `isSupported()` for /debugger-mcp paths

- [ ] **2.9** Implement SSE endpoint handling
  - [ ] Handle GET /debugger-mcp/sse
  - [ ] Send SSE headers (text/event-stream, no-cache, keep-alive)
  - [ ] Send endpoint event with POST URL
  - [ ] Add CORS headers

- [ ] **2.10** Implement POST endpoint handling
  - [ ] Handle POST /debugger-mcp
  - [ ] Parse request body
  - [ ] Route to JsonRpcHandler (on coroutine scope)
  - [ ] Return JSON response with CORS headers

- [ ] **2.11** Register handler in `plugin.xml`
  - [ ] Add `<httpRequestHandler>` extension

- [ ] **2.12** Test server manually
  - [ ] Start IDE with plugin
  - [ ] Verify SSE endpoint responds
  - [ ] Verify POST endpoint responds
  - [ ] Test initialize request
  - [ ] Test tools/list request

**Phase 2 Deliverables**:
- Working HTTP+SSE transport
- JSON-RPC message handling
- Server responds to initialize and tools/list

---

## Phase 3: Tool Framework

**Goal**: Create base tool infrastructure, registry, and helper classes for debugger operations.

**Prerequisites**: Phase 2 complete

### Tasks

- [ ] **3.1** Create `tools/McpTool.kt` interface
  - [ ] Define `name: String` property
  - [ ] Define `description: String` property
  - [ ] Define `inputSchema: JsonObject` property
  - [ ] Define `suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult`

- [ ] **3.2** Create `tools/AbstractMcpTool.kt`
  - [ ] Configure Json serializer
  - [ ] Implement `checkCanceled()` using ProgressManager
  - [ ] Implement `createSuccessResult(text: String)`
  - [ ] Implement `createErrorResult(message: String)`
  - [ ] Implement `createJsonResult<T>(data: T)`

- [ ] **3.3** Add debugger helper methods to AbstractMcpTool
  - [ ] Implement `getDebuggerManager(project): XDebuggerManager`
  - [ ] Implement `getCurrentSession(project): XDebugSession?`
  - [ ] Implement `getSessionById(project, sessionId): XDebugSession?`
  - [ ] Implement `resolveSession(project, sessionId?): XDebugSession?`
  - [ ] Add `projectPathProperty()` helper for schema generation

- [ ] **3.4** Create `tools/ToolRegistry.kt`
  - [ ] Use ConcurrentHashMap for thread-safe storage
  - [ ] Implement `register(tool: McpTool)`
  - [ ] Implement `unregister(toolName: String)`
  - [ ] Implement `getTool(name: String): McpTool?`
  - [ ] Implement `getAllTools(): List<McpTool>`
  - [ ] Implement `getToolDefinitions(): List<ToolDefinition>`

- [ ] **3.5** Create `tools/models/SessionModels.kt`
  - [ ] Define `DebugSessionInfo` data class
  - [ ] Define `DebugSessionStatus` data class (rich status)
  - [ ] Define `BreakpointHitInfo` data class

- [ ] **3.6** Create `tools/models/BreakpointModels.kt`
  - [ ] Define `BreakpointInfo` data class
  - [ ] Define `SetBreakpointResult` data class

- [ ] **3.7** Create `tools/models/StackModels.kt`
  - [ ] Define `StackFrameInfo` data class
  - [ ] Define `ThreadInfo` data class
  - [ ] Define `SourceLocation` data class

- [ ] **3.8** Create `tools/models/VariableModels.kt`
  - [ ] Define `VariableInfo` data class
  - [ ] Define `WatchInfo` data class

- [ ] **3.9** Create `tools/models/EvaluationModels.kt`
  - [ ] Define `EvaluationResult` data class

- [ ] **3.10** Create `tools/models/RunConfigModels.kt`
  - [ ] Define `RunConfigurationInfo` data class
  - [ ] Define `SourceContext` and `SourceLine` data classes

**Phase 3 Deliverables**:
- McpTool interface and AbstractMcpTool base class
- ToolRegistry for managing tools
- All data models for tool responses

---

## Phase 4: P0 Tools - Core Debugging

**Goal**: Implement all Priority 0 tools for essential debugging functionality.

**Prerequisites**: Phase 3 complete

### 4.1 Run Configuration Tools

- [ ] **4.1.1** Create `tools/runconfig/ListRunConfigurationsTool.kt`
  - [ ] Define name, description, inputSchema
  - [ ] Get RunManager.getInstance(project)
  - [ ] List all configurations with type, name, canDebug
  - [ ] Return as JSON array

- [ ] **4.1.2** Create `tools/runconfig/RunConfigurationTool.kt`
  - [ ] Define name, description, inputSchema (name, mode)
  - [ ] Find configuration by name
  - [ ] Start with debug or run mode based on parameter
  - [ ] Return session info or error

### 4.2 Debug Session Tools

- [ ] **4.2.1** Create `tools/session/ListDebugSessionsTool.kt`
  - [ ] Define name, description, inputSchema
  - [ ] Get all debug sessions from XDebuggerManager
  - [ ] Return list of DebugSessionInfo

- [ ] **4.2.2** Create `tools/session/StartDebugSessionTool.kt`
  - [ ] Define name, description, inputSchema (configuration_name)
  - [ ] Find run configuration by name
  - [ ] Start debug session using ExecutionEnvironmentBuilder
  - [ ] Return new session info

- [ ] **4.2.3** Create `tools/session/StopDebugSessionTool.kt`
  - [ ] Define name, description, inputSchema (session_id?)
  - [ ] Resolve session (current or by ID)
  - [ ] Call session.stop()
  - [ ] Return confirmation

- [ ] **4.2.4** Create `tools/session/GetDebugSessionStatusTool.kt`
  - [ ] Define comprehensive inputSchema with all options
  - [ ] Implement full status gathering:
    - [ ] Session state (running/paused)
    - [ ] Pause reason detection
    - [ ] Current location
    - [ ] Stack summary (limited frames)
    - [ ] Variables (if requested)
    - [ ] Watches
    - [ ] Source context (if requested)
    - [ ] Thread info
  - [ ] Handle async callbacks with suspendCancellableCoroutine

### 4.3 Breakpoint Tools

- [ ] **4.3.1** Create `tools/breakpoint/ListBreakpointsTool.kt`
  - [ ] Define name, description, inputSchema
  - [ ] Get XBreakpointManager
  - [ ] List all breakpoints with properties
  - [ ] Return as JSON array

- [ ] **4.3.2** Create `tools/breakpoint/SetBreakpointTool.kt`
  - [ ] Define name, description, inputSchema (file_path, line, condition?, log_message?, etc.)
  - [ ] Find VirtualFile from path
  - [ ] Find appropriate XLineBreakpointType
  - [ ] Create breakpoint with writeAction on EDT
  - [ ] Configure condition, log message, suspend policy
  - [ ] Return breakpoint info

- [ ] **4.3.3** Create `tools/breakpoint/RemoveBreakpointTool.kt`
  - [ ] Define name, description, inputSchema (breakpoint_id)
  - [ ] Find breakpoint by ID
  - [ ] Remove with writeAction on EDT
  - [ ] Return confirmation

### 4.4 Execution Control Tools

- [ ] **4.4.1** Create `tools/execution/ResumeTool.kt`
  - [ ] Define name, description, inputSchema (session_id?)
  - [ ] Resolve session
  - [ ] Call session.resume()
  - [ ] Return confirmation

- [ ] **4.4.2** Create `tools/execution/PauseTool.kt`
  - [ ] Define name, description, inputSchema (session_id?)
  - [ ] Resolve session
  - [ ] Call session.pause()
  - [ ] Return confirmation

- [ ] **4.4.3** Create `tools/execution/StepOverTool.kt`
  - [ ] Define name, description, inputSchema (session_id?)
  - [ ] Resolve session
  - [ ] Call session.stepOver(false)
  - [ ] Return confirmation

- [ ] **4.4.4** Create `tools/execution/StepIntoTool.kt`
  - [ ] Define name, description, inputSchema (session_id?)
  - [ ] Resolve session
  - [ ] Call session.stepInto()
  - [ ] Return confirmation

### 4.5 Stack & Variable Tools

- [ ] **4.5.1** Create `tools/stack/GetStackTraceTool.kt`
  - [ ] Define name, description, inputSchema (session_id?, max_frames?)
  - [ ] Resolve session
  - [ ] Get current stack frame and compute children
  - [ ] Build list of StackFrameInfo
  - [ ] Return as JSON array

- [ ] **4.5.2** Create `tools/variable/GetVariablesTool.kt`
  - [ ] Define name, description, inputSchema (session_id?, frame_index?, scope?)
  - [ ] Resolve session and frame
  - [ ] Get variables using computeChildren callback
  - [ ] Convert to coroutine with suspendCancellableCoroutine
  - [ ] Return list of VariableInfo

### 4.6 Evaluation Tool

- [ ] **4.6.1** Create `tools/evaluation/EvaluateTool.kt`
  - [ ] Define name, description, inputSchema (expression, session_id?, frame_index?)
  - [ ] Resolve session and frame
  - [ ] Get XDebuggerEvaluator
  - [ ] Evaluate expression with callback
  - [ ] Convert to coroutine
  - [ ] Return EvaluationResult

### 4.7 Tool Registration

- [ ] **4.7.1** Implement `registerBuiltInTools()` in ToolRegistry
  - [ ] Register all P0 tools
  - [ ] Call from McpServerService.init

- [ ] **4.7.2** Test P0 tools manually
  - [ ] Test each tool with curl or similar
  - [ ] Verify responses are correct
  - [ ] Test error cases

**Phase 4 Deliverables**:
- All 15 P0 tools implemented and working
- Tools registered and accessible via MCP

---

## Phase 5: Command History Service

**Goal**: Implement command history tracking for the GUI.

**Prerequisites**: Phase 4 (at least partially complete for testing)

### Tasks

- [ ] **5.1** Create `history/CommandModels.kt`
  - [ ] Define `CommandEntry` data class
  - [ ] Define `CommandStatus` enum (PENDING, SUCCESS, ERROR)
  - [ ] Define `CommandFilter` data class
  - [ ] Define `CommandHistoryListener` interface

- [ ] **5.2** Create `history/CommandHistoryService.kt`
  - [ ] Annotate as `@Service(Service.Level.PROJECT)`
  - [ ] Use synchronized list for thread safety
  - [ ] Use CopyOnWriteArrayList for listeners

- [ ] **5.3** Implement history management methods
  - [ ] Implement `addCommand(entry: CommandEntry)`
  - [ ] Implement `updateCommandStatus(id, status, result, durationMs?)`
  - [ ] Implement `clearHistory()`
  - [ ] Implement `getFilteredHistory(filter: CommandFilter)`

- [ ] **5.4** Implement listener management
  - [ ] Implement `addListener(listener)`
  - [ ] Implement `removeListener(listener)`
  - [ ] Implement `notifyListeners()` (invoke on EDT)

- [ ] **5.5** Implement history size limit
  - [ ] Read maxHistorySize from settings
  - [ ] Trim oldest entries when limit exceeded

- [ ] **5.6** Integrate with JsonRpcHandler
  - [ ] Record command start in processToolCall
  - [ ] Update command status after execution
  - [ ] Track duration

- [ ] **5.7** Add export functionality
  - [ ] Define `CommandEntryExport` for serialization
  - [ ] Implement `exportToJson(): String`
  - [ ] Implement `exportToCsv(): String`

- [ ] **5.8** Register service in `plugin.xml`
  - [ ] Add `<projectService>` extension

**Phase 5 Deliverables**:
- CommandHistoryService tracking all tool calls
- Listener pattern for GUI updates
- Export functionality

---

## Phase 6: GUI - Tool Window

**Goal**: Implement the tool window UI matching the index plugin design.

**Prerequisites**: Phase 5 complete

### 6.1 Basic Tool Window

- [ ] **6.1.1** Create `ui/McpToolWindowFactory.kt`
  - [ ] Implement ToolWindowFactory interface
  - [ ] Implement DumbAware marker interface
  - [ ] Create toolbar with action group
  - [ ] Create Install button (right side)
  - [ ] Layout toolbar and main panel

- [ ] **6.1.2** Register tool window in `plugin.xml`
  - [ ] Add `<toolWindow>` extension
  - [ ] Set anchor="bottom"
  - [ ] Set appropriate icon

### 6.2 Server Status Panel

- [ ] **6.2.1** Create `ui/ServerStatusPanel.kt`
  - [ ] Display status indicator (● Running/Stopped)
  - [ ] Display server URL
  - [ ] Display current project name
  - [ ] Implement `refresh()` method
  - [ ] Use appropriate colors (green for running)

### 6.3 Agent Rule Tip Panel

- [ ] **6.3.1** Create `ui/AgentRuleTipPanel.kt`
  - [ ] Use yellow background (JBColor for light/dark)
  - [ ] Add info icon
  - [ ] Add tip text
  - [ ] Add "Copy rule" link with hover effect
  - [ ] Implement copy to clipboard
  - [ ] Show notification after copy

- [ ] **6.3.2** Define AGENT_RULE_TEXT constant
  - [ ] Write appropriate rule text for debugger plugin

### 6.4 Filter Toolbar

- [ ] **6.4.1** Create `ui/FilterToolbar.kt`
  - [ ] Add tool name filter (ComboBox)
  - [ ] Add status filter (ComboBox)
  - [ ] Add search field
  - [ ] Implement filter callback

- [ ] **6.4.2** Populate tool name filter dynamically
  - [ ] Get tool names from registry
  - [ ] Add "All" option

### 6.5 Command History List

- [ ] **6.5.1** Create `ui/CommandListCellRenderer.kt`
  - [ ] Display timestamp (gray, formatted)
  - [ ] Display tool name (bold)
  - [ ] Display status with color coding
  - [ ] Handle selection highlighting

### 6.6 Main Panel

- [ ] **6.6.1** Create `ui/McpToolWindowPanel.kt`
  - [ ] Implement Disposable interface
  - [ ] Implement CommandHistoryListener interface
  - [ ] Layout all subcomponents
  - [ ] Create JBSplitter (60/40) for list/details

- [ ] **6.6.2** Implement history list
  - [ ] Use JBList with DefaultListModel
  - [ ] Set CommandListCellRenderer
  - [ ] Handle selection changes

- [ ] **6.6.3** Implement details area
  - [ ] Use JBTextArea with monospace font
  - [ ] Format command details (tool, status, duration, params, result)
  - [ ] Update on selection change

- [ ] **6.6.4** Implement listener callbacks
  - [ ] Handle onCommandAdded (add to top, auto-scroll)
  - [ ] Handle onCommandUpdated (update in place)
  - [ ] Handle onHistoryCleared

- [ ] **6.6.5** Implement refresh functionality
  - [ ] Refresh server status
  - [ ] Refresh history list

### 6.7 Icons

- [ ] **6.7.1** Create/add tool window icon
  - [ ] Add `icons/toolWindow.svg`
  - [ ] Create McpIcons object for icon references

**Phase 6 Deliverables**:
- Complete tool window matching index plugin design
- Real-time command history display
- Filtering and search
- Details panel

---

## Phase 7: P1 Tools - Enhanced Features

**Goal**: Implement Priority 1 tools for enhanced debugging capabilities.

**Prerequisites**: Phase 4 complete

### Tasks

- [ ] **7.1** Create `tools/execution/StepOutTool.kt`
  - [ ] Define name, description, inputSchema
  - [ ] Call session.stepOut()
  - [ ] Return confirmation

- [ ] **7.2** Create `tools/execution/RunToLineTool.kt`
  - [ ] Define name, description, inputSchema (file_path, line, session_id?)
  - [ ] Create XSourcePosition from file and line
  - [ ] Call session.runToPosition()
  - [ ] Return confirmation

- [ ] **7.3** Create `tools/stack/SelectStackFrameTool.kt`
  - [ ] Define name, description, inputSchema (frame_index, session_id?)
  - [ ] Get stack frames
  - [ ] Set current frame to specified index
  - [ ] Return frame info

- [ ] **7.4** Create `tools/stack/ListThreadsTool.kt`
  - [ ] Define name, description, inputSchema (session_id?)
  - [ ] Get all threads from debug process
  - [ ] Return list of ThreadInfo

- [ ] **7.5** Create `tools/variable/ExpandVariableTool.kt`
  - [ ] Define name, description, inputSchema (variable_id, session_id?)
  - [ ] Find variable by ID
  - [ ] Compute children
  - [ ] Return list of child VariableInfo

- [ ] **7.6** Create `tools/navigation/GetSourceContextTool.kt`
  - [ ] Define name, description, inputSchema (file_path?, line?, lines_before?, lines_after?, session_id?)
  - [ ] If no file_path, use current position
  - [ ] Read file content
  - [ ] Extract lines around target
  - [ ] Include breakpoint markers
  - [ ] Return SourceContext

- [ ] **7.7** Register P1 tools in ToolRegistry
  - [ ] Add to registerBuiltInTools()

- [ ] **7.8** Test P1 tools
  - [ ] Manual testing with curl
  - [ ] Verify integration with GUI

**Phase 7 Deliverables**:
- All 6 P1 tools implemented
- Enhanced debugging workflow support

---

## Phase 8: P2 Tools - Advanced Features

**Goal**: Implement Priority 2 tools for advanced debugging scenarios.

**Prerequisites**: Phase 7 complete

### Tasks

- [ ] **8.1** Create `tools/breakpoint/SetExceptionBreakpointTool.kt`
  - [ ] Define name, description, inputSchema (exception_class, caught?, uncaught?, enabled?)
  - [ ] Find JavaExceptionBreakpointType
  - [ ] Create exception breakpoint
  - [ ] Return breakpoint info

- [ ] **8.2** Create `tools/variable/SetVariableTool.kt`
  - [ ] Define name, description, inputSchema (variable_name, new_value, session_id?, frame_index?)
  - [ ] Find variable in current frame
  - [ ] Use XValueModifier to set value
  - [ ] Return confirmation

- [ ] **8.3** Create `tools/watch/AddWatchTool.kt`
  - [ ] Define name, description, inputSchema (expression, session_id?)
  - [ ] Add watch expression to session
  - [ ] Return watch info

- [ ] **8.4** Create `tools/watch/RemoveWatchTool.kt`
  - [ ] Define name, description, inputSchema (watch_id, session_id?)
  - [ ] Find watch by ID
  - [ ] Remove watch
  - [ ] Return confirmation

- [ ] **8.5** Register P2 tools in ToolRegistry
  - [ ] Add to registerBuiltInTools()

- [ ] **8.6** Test P2 tools
  - [ ] Manual testing
  - [ ] Edge case testing

- [ ] **8.7** Update tool count in documentation
  - [ ] Verify all 26 tools are registered

- [ ] **8.8** Full integration test
  - [ ] Test complete debugging workflow
  - [ ] Verify all tools work together

**Phase 8 Deliverables**:
- All 26 tools implemented
- Complete debugging capability

---

## Phase 9: Settings & Actions

**Goal**: Implement settings persistence and toolbar actions.

**Prerequisites**: Phase 6 complete

### 9.1 Settings

- [ ] **9.1.1** Create `settings/McpSettings.kt`
  - [ ] Annotate with @Service and @State
  - [ ] Define State data class (maxHistorySize, autoScroll)
  - [ ] Implement PersistentStateComponent interface
  - [ ] Add companion object with getInstance()

- [ ] **9.1.2** Create `settings/McpSettingsConfigurable.kt`
  - [ ] Implement Configurable interface
  - [ ] Create settings UI panel
  - [ ] Handle apply/reset

- [ ] **9.1.3** Register settings in `plugin.xml`
  - [ ] Add `<applicationService>` for McpSettings
  - [ ] Add `<applicationConfigurable>` for settings page

### 9.2 Actions

- [ ] **9.2.1** Create `actions/RefreshAction.kt`
  - [ ] Trigger panel refresh
  - [ ] Set appropriate icon

- [ ] **9.2.2** Create `actions/CopyServerUrlAction.kt`
  - [ ] Copy server URL to clipboard
  - [ ] Show notification

- [ ] **9.2.3** Create `actions/ClearHistoryAction.kt`
  - [ ] Clear command history
  - [ ] Show confirmation dialog

- [ ] **9.2.4** Create `actions/ExportHistoryAction.kt`
  - [ ] Show file chooser dialog
  - [ ] Support JSON and CSV formats
  - [ ] Write to selected file
  - [ ] Show success/error notification

- [ ] **9.2.5** Create `actions/CopyClientConfigAction.kt`
  - [ ] Create popup with two sections
  - [ ] Implement "Install Now" section (Claude Code CLI)
  - [ ] Implement "Copy Configuration" section (Claude Desktop, Cursor, VS Code, Windsurf)
  - [ ] Execute CLI command in background thread
  - [ ] Show appropriate notifications

- [ ] **9.2.6** Create `util/ClientConfigGenerator.kt`
  - [ ] Define ClientType enum
  - [ ] Implement config generation for each client type
  - [ ] Implement buildClaudeCodeCommand()
  - [ ] Implement getConfigLocationHint() for each type

### 9.3 Registration

- [ ] **9.3.1** Register actions in `plugin.xml`
  - [ ] Create action group
  - [ ] Register all actions

- [ ] **9.3.2** Register notification group in `plugin.xml`
  - [ ] Add `<notificationGroup>`

**Phase 9 Deliverables**:
- Persistent settings with UI
- All toolbar actions working
- Client configuration generator

---

## Phase 10: Testing & Polish

**Goal**: Add tests, documentation, and final polish.

**Prerequisites**: All previous phases complete

### 10.1 Unit Tests

- [ ] **10.1.1** Create test structure
  ```
  src/test/kotlin/
  ├── server/
  ├── tools/
  ├── history/
  ├── util/
  └── integration/
  ```

- [ ] **10.1.2** Write JsonRpcHandler tests
  - [ ] Test request parsing
  - [ ] Test method routing
  - [ ] Test error handling

- [ ] **10.1.3** Write ToolRegistry tests
  - [ ] Test registration
  - [ ] Test lookup
  - [ ] Test getToolDefinitions

- [ ] **10.1.4** Write ProjectUtils tests
  - [ ] Test single project resolution
  - [ ] Test multiple project handling

- [ ] **10.1.5** Write CommandHistoryService tests
  - [ ] Test add/update/clear
  - [ ] Test filtering
  - [ ] Test listener notifications

### 10.2 Integration Tests

- [ ] **10.2.1** Write tool integration tests
  - [ ] Test SetBreakpointTool with test fixture
  - [ ] Test basic execution flow

- [ ] **10.2.2** Write end-to-end test
  - [ ] Start debug session
  - [ ] Set breakpoint
  - [ ] Verify pause
  - [ ] Step through code
  - [ ] Inspect variables

### 10.3 Documentation

- [ ] **10.3.1** Update README.md
  - [ ] Installation instructions
  - [ ] Configuration guide
  - [ ] Tool reference

- [ ] **10.3.2** Add CHANGELOG.md
  - [ ] Document initial release features

### 10.4 Final Polish

- [ ] **10.4.1** Review all TODO comments
  - [ ] Address or document remaining items

- [ ] **10.4.2** Code cleanup
  - [ ] Remove unused imports
  - [ ] Ensure consistent formatting
  - [ ] Check for any hardcoded strings

- [ ] **10.4.3** Final testing
  - [ ] Test on fresh IDE installation
  - [ ] Test with different JetBrains IDEs (IntelliJ, PyCharm, etc.)

- [ ] **10.4.4** Prepare for release
  - [ ] Update version number
  - [ ] Build release artifact
  - [ ] Test installation from artifact

**Phase 10 Deliverables**:
- Comprehensive test suite
- Updated documentation
- Production-ready plugin

---

## Implementation Notes

### Critical Paths

1. **Server → Tools → GUI** is the main dependency chain
2. Command History must be ready before GUI
3. P0 tools should be prioritized for basic functionality

### Risk Areas

| Area | Risk | Mitigation |
|------|------|------------|
| Async debugger callbacks | Complex conversion to coroutines | Use tested patterns from design.md |
| EDT/BGT threading | UI freezes if done wrong | Strict dispatcher usage |
| Multi-project support | Edge cases | Comprehensive testing |
| Different IDE versions | API compatibility | Test on multiple versions |

### Testing Checkpoints

After each phase, verify:
1. Project still builds
2. Plugin loads in IDE
3. No runtime exceptions
4. Previous functionality still works

---

## Change Log

| Date | Change |
|------|--------|
| 2025-12-01 | Initial task breakdown created |

