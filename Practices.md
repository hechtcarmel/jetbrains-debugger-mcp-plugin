# Best Practices Guide

This document outlines best practices for three key areas relevant to this project:
1. Model Context Protocol (MCP) Server Implementation
2. IntelliJ Platform SDK Plugin Development
3. IntelliJ Debugger API Usage

---

## 1. Model Context Protocol (MCP) Best Practices

### 1.1 Architectural Design Principles

#### Single Responsibility Principle
Each MCP server should have **one clear, well-defined purpose**:
- Improves maintainability and testability
- Enables independent scaling of components
- Prevents failure cascades between unrelated features
- Clarifies ownership boundaries

#### Tool Design Guidelines

**Naming Conventions:**
- Use descriptive names in lowercase with underscores (e.g., `set_breakpoint`, `evaluate_expression`)
- Avoid mapping every API endpoint to a new MCP tool
- Group related tasks and design higher-level functions

**Input Schema Best Practices:**
```kotlin
// Good: Clear, typed schema with descriptions
Tool.Input(
    properties = buildJsonObject {
        putJsonObject("file_path") {
            put("type", "string")
            put("description", "Absolute path to the file")
        }
        putJsonObject("line_number") {
            put("type", "integer")
            put("description", "1-based line number")
            put("minimum", 1)
        }
    },
    required = listOf("file_path", "line_number")
)
```

**Response Handling:**
- Return `CallToolResult(content, isError = false)` for success
- Return `CallToolResult(content, isError = true)` for errors with descriptive messages
- Include structured content when possible for better LLM parsing

### 1.2 Transport Selection

| Transport | Use Case | Pros | Cons |
|-----------|----------|------|------|
| **STDIO** | Local IDE plugins | Simple, no network setup | Single client only |
| **SSE (Server-Sent Events)** | Web-based clients | HTTP-based, firewall-friendly | Unidirectional, needs POST endpoint |
| **Streamable HTTP** | Modern web clients | Replaces SSE, bidirectional | Newer, less client support |
| **WebSocket** | Real-time applications | Full duplex, low latency | More complex setup |

**Recommendation for this plugin:** Use STDIO as the primary transport (via JetBrains mcp-proxy) with optional SSE for direct HTTP access.

### 1.3 Security Best Practices

1. **Defense in Depth** - Layer security across multiple levels:
   - Network isolation (bind to localhost only)
   - Authentication (identity verification)
   - Authorization (granular permissions)
   - Input validation (sanitize all inputs)
   - Audit logging (track all operations)

2. **Sensitive Operations:**
   - Always require user confirmation for destructive operations
   - Implement "brave mode" as opt-in, not default
   - Never expose credentials or sensitive data in responses

3. **Resource Indicators:**
   - MCP servers are classified as OAuth Resource Servers
   - Use RFC 8707 resource indicators for token scoping

### 1.4 Error Handling Patterns

```kotlin
// Comprehensive error handling pattern
server.addTool(name = "my_tool", description = "...") { request ->
    try {
        // Validate inputs
        val param = request.params.arguments["param"]?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Error: 'param' is required")),
                isError = true
            )

        // Execute operation
        val result = performOperation(param)

        CallToolResult(
            content = listOf(TextContent(result)),
            isError = false
        )
    } catch (e: IllegalArgumentException) {
        CallToolResult(
            content = listOf(TextContent("Invalid argument: ${e.message}")),
            isError = true
        )
    } catch (e: Exception) {
        CallToolResult(
            content = listOf(TextContent("Internal error: ${e.message}")),
            isError = true
        )
    }
}
```

### 1.5 Performance Targets

- **Throughput:** >1000 requests/second per instance
- **Latency P95:** <100ms for simple operations
- **Latency P99:** <500ms for complex operations
- **Error Rate:** <0.1% under normal conditions

### 1.6 MCP Kotlin SDK Patterns

**Server Setup:**
```kotlin
val server = Server(
    serverInfo = Implementation(name = "debugger-mcp", version = "1.0.0"),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true)
        )
    )
) { "IntelliJ Debugger MCP Server" }
```

**Tool Registration:**
```kotlin
server.addTool(
    name = "tool_name",
    description = "Clear description for LLM",
    inputSchema = Tool.Input(/* schema */),
    toolAnnotations = Annotations(
        destructive = false,
        readOnly = true
    )
) { request -> /* handler */ }
```

---

## 2. IntelliJ Platform SDK Best Practices

### 2.1 Plugin Architecture

#### Service Levels
- **Application-level services:** Shared across all projects
- **Project-level services:** Scoped to a single project (preferred for debugger operations)

```kotlin
@Service(Service.Level.PROJECT)
class DebuggerMcpService(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) {
    // Service implementation
}
```

#### Extension Points
Register extensions in `plugin.xml`:
```xml
<extensions defaultExtensionNs="com.intellij">
    <projectService
        serviceImplementation="com.example.DebuggerMcpService"/>
</extensions>
```

### 2.2 Threading Model

#### Event Dispatch Thread (EDT)
- Single thread for UI events
- Operations must be fast (<50ms)
- Required for UI updates and write actions

#### Background Threads (BGT)
- Used for long-running operations
- Never block with locks or I/O
- Use progress indicators for user feedback

#### Read/Write Actions

**Read Actions:**
```kotlin
// Suspending read action (preferred in coroutines)
val result = readAction {
    // Access PSI, VFS, or project model
}

// Non-blocking read action (cancels on write)
readActionCancellable {
    // Long-running read operation
}
```

**Write Actions:**
```kotlin
// Must run on EDT
writeAction {
    // Modify PSI, VFS, or project model
}
```

### 2.3 Coroutine Best Practices

#### Service Scope Injection
```kotlin
@Service(Service.Level.PROJECT)
class MyService(
    private val project: Project,
    private val scope: CoroutineScope  // Injected, tied to service lifecycle
) {
    fun doAsync() {
        scope.launch {
            // Coroutine work
        }
    }
}
```

#### Dispatcher Selection
| Dispatcher | Use Case |
|------------|----------|
| `Dispatchers.Default` | CPU-intensive work |
| `Dispatchers.IO` | Blocking I/O operations |
| `Dispatchers.EDT` | UI updates (IntelliJ-specific) |
| `Dispatchers.Main` | UI but NO read/write actions |

#### Read Actions in Coroutines
```kotlin
// Write-allowing read action (cancels on write)
val data = readAction {
    collectData()
}

// Write-blocking read action (blocks writes until complete)
val data = readActionBlocking {
    collectData()
}
```

### 2.4 Plugin Dependencies

For debugger functionality, add to `gradle.properties`:
```properties
platformBundledPlugins = com.intellij.java
```

And in `plugin.xml`:
```xml
<depends>com.intellij.modules.platform</depends>
<depends optional="true" config-file="java-support.xml">com.intellij.java</depends>
```

### 2.5 Error Handling

```kotlin
// Use thisLogger() for logging
import com.intellij.openapi.diagnostic.thisLogger

class MyService {
    fun doSomething() {
        try {
            // Operation
        } catch (e: Exception) {
            thisLogger().error("Operation failed", e)
        }
    }
}
```

### 2.6 JetBrains MCP Server Integration

Since IntelliJ 2025.2, MCP server functionality is built-in. To extend it:

1. **Extension Point:** `com.intellij.mcpServer`
2. **Tool Registration:** Via `plugin.xml` or programmatic API
3. **Reference:** [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin)

---

## 3. IntelliJ Debugger API Best Practices

### 3.1 Core Classes Overview

| Class | Purpose |
|-------|---------|
| `XDebuggerManager` | Entry point for debugger operations |
| `XDebugSession` | Active debug session control |
| `XDebugProcess` | Debug process lifecycle |
| `XStackFrame` | Stack frame and variables |
| `XDebuggerEvaluator` | Expression evaluation |
| `XBreakpointManager` | Breakpoint CRUD operations |

### 3.2 Accessing Debug Sessions

```kotlin
// Get debugger manager
val debuggerManager = XDebuggerManager.getInstance(project)

// Get all active sessions
val sessions: Array<XDebugSession> = debuggerManager.debugSessions

// Get current (focused) session
val currentSession: XDebugSession? = debuggerManager.currentSession
```

### 3.3 Listening to Debug Events

```kotlin
// Subscribe to debugger events
project.messageBus.connect(disposable).subscribe(
    XDebuggerManager.TOPIC,
    object : XDebuggerManagerListener {
        override fun processStarted(debugProcess: XDebugProcess) {
            // Debug session started
            debugProcess.session.addSessionListener(
                object : XDebugSessionListener {
                    override fun sessionPaused() {
                        // Debugger hit breakpoint or stepped
                    }
                    override fun sessionResumed() {
                        // Debugger resumed execution
                    }
                    override fun sessionStopped() {
                        // Debug session ended
                    }
                },
                disposable
            )
        }
    }
)
```

### 3.4 Execution Control

```kotlin
val session: XDebugSession = /* get session */

// Pause execution
session.pause()

// Resume execution
session.resume()

// Step operations
session.stepOver(ignoreBreakpoints = false)
session.stepInto()
session.stepOut()

// Run to position
session.runToPosition(sourcePosition, ignoreBreakpoints = false)
```

### 3.5 Stack Frame and Variables

```kotlin
// Get current stack frame
val frame: XStackFrame? = session.currentStackFrame

// Get source position
val position: XSourcePosition? = frame?.sourcePosition

// Compute variables (async)
frame?.computeChildren(object : XCompositeNode {
    override fun addChildren(
        children: XValueChildrenList,
        last: Boolean
    ) {
        for (i in 0 until children.size()) {
            val name = children.getName(i)
            val value = children.getValue(i)
            // Process variable
        }
    }

    override fun setAlreadySorted(alreadySorted: Boolean) {}
    override fun setErrorMessage(errorMessage: String) {}
    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {}
    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
})
```

### 3.6 Expression Evaluation

```kotlin
val evaluator: XDebuggerEvaluator? = frame?.evaluator

evaluator?.evaluate(
    expression = "myVariable + 10",
    callback = object : XDebuggerEvaluator.XEvaluationCallback {
        override fun evaluated(result: XValue) {
            // Handle successful evaluation
            result.computePresentation(/* node */, XValuePlace.TREE)
        }

        override fun errorOccurred(errorMessage: String) {
            // Handle evaluation error
        }
    },
    expressionPosition = null
)
```

### 3.7 Breakpoint Management

```kotlin
val breakpointManager = debuggerManager.breakpointManager

// Get all breakpoints
val allBreakpoints: Array<XBreakpoint<*>> = breakpointManager.allBreakpoints

// Add line breakpoint
val lineBreakpoint = breakpointManager.addLineBreakpoint(
    JavaLineBreakpointType::class.java,
    fileUrl,       // "file:///path/to/file.java"
    lineNumber,    // 0-based line number
    properties,    // null for default
    temporary      // false for persistent
)

// Remove breakpoint
breakpointManager.removeBreakpoint(breakpoint)

// Find breakpoints at line
val breakpointsAtLine = breakpointManager.findBreakpointsAtLine(
    JavaLineBreakpointType::class.java,
    virtualFile,
    lineNumber
)
```

### 3.8 Async Considerations

**Important:** Most debugger operations are asynchronous:

```kotlin
// BAD: Blocking on async result
val variables = mutableListOf<String>()
frame.computeChildren(/* callback that fills variables */)
return variables // May be empty!

// GOOD: Use callbacks or coroutines
suspend fun getVariables(frame: XStackFrame): List<Variable> {
    return suspendCancellableCoroutine { continuation ->
        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                if (last) {
                    val result = (0 until children.size()).map { i ->
                        Variable(children.getName(i), children.getValue(i))
                    }
                    continuation.resume(result)
                }
            }
            // ... other overrides
        })
    }
}
```

### 3.9 Watch Expressions

```kotlin
// Watches are managed per-session, access via evaluator
val evaluator = session.currentStackFrame?.evaluator

// Evaluate watch expression
evaluator?.evaluate(
    watchExpression,
    callback,
    expressionPosition = null
)
```

### 3.10 Run Configurations

```kotlin
// Get run configurations
val runManager = RunManager.getInstance(project)
val configurations = runManager.allConfigurationsList

// Start debug session programmatically
val executor = DefaultDebugExecutor.getDebugExecutorInstance()
val environment = ExecutionEnvironmentBuilder
    .create(executor, runConfiguration)
    .build()

ProgramRunnerUtil.executeConfiguration(environment, false, true)
```

---

## References

### MCP Resources
- [MCP Specification](https://modelcontextprotocol.io/specification)
- [MCP Best Practices](https://modelcontextprotocol.info/docs/best-practices/)
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Anthropic MCP Announcement](https://www.anthropic.com/news/model-context-protocol)

### IntelliJ Platform Resources
- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/intellij/about.html)
- [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Coroutine Read Actions](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html)
- [Services Guide](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)
- [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin)

### Debugger API Resources
- [XDebugProcess Source](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugProcess.java)
- [XDebugSession Source](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSession.java)
- [XDebuggerEvaluator Source](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/evaluation/XDebuggerEvaluator.java)
- [XBreakpointManager Source](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/XBreakpointManager.java)
