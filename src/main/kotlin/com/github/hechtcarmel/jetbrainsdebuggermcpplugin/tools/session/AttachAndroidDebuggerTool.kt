package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AttachAndroidDebuggerTool internal constructor(
    private val backend: AndroidAttachBackend? = null
) : AbstractMcpTool() {
    override val name = "attach_android_debugger"
    override val description = """
        Attaches the IDE's Android debugger to an existing Android app process and returns the session ID, without installing or launching the app or opening a process chooser.
        Call list_android_processes first, then supply process_name or process_id and device_serial when needed. Names match exactly, including :subprocess suffixes. Both selectors, when supplied, must match; ambiguous targets return an error listing candidates.
        Requires the Android plugin and a connected Android device or emulator. Defaults to Android's Java/Kotlin debugger (debugger_id: Java); other debugger IDs depend on installed Android debuggers.
        New attachments use default debugger state and do not inherit a run configuration's LLDB startup commands or symbol directories. An existing matching debug session in the same project may be reused.
    """.trimIndent()
    override val annotations = ToolAnnotationPresets.mutable("Attach Android Debugger")
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (key, schema) = projectPathProperty()
            put(key, schema)
            put("process_id", integerProperty("PID of the running Android process; required unless process_name is supplied", minimum = 1))
            put("process_name", stringProperty("Exact Android process name including any :subprocess suffix; required unless process_id is supplied"))
            put("device_serial", stringProperty("Exact Android device serial; required when the process selector matches multiple devices"))
            put("debugger_id", stringProperty("Android debugger ID; defaults to Java for Java/Kotlin. Other IDs depend on installed Android debuggers.", default = "Java"))
        }
        putJsonArray("required") { }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        if (ToolArguments.optionalString(arguments, "configuration_name") != null) {
            throw ToolExecutionError("attach_android_debugger does not accept configuration_name. Use start_debug_session to launch a configuration.")
        }
        val selector = AndroidProcessSelector.from(arguments)
        val debuggerId = ToolArguments.optionalString(arguments, "debugger_id") ?: "Java"
        return try {
            val (target, session) = withContext(Dispatchers.IO) {
                withAndroidBackend(backend) { active ->
                    val candidates = active.processes(project)
                    val info = selector.select(candidates.map { it.info })
                    val target = candidates.single { it.info == info }
                    target to active.attach(project, target, debuggerId)
                }
            }
            createJsonResult(StartDebugSessionResult(
                status = "started",
                message = "Debugger attached to Android process ${target.info.processName ?: target.info.processId} " +
                    "(PID ${target.info.processId}) on ${target.info.deviceSerial}",
                session = DebugSessionInfo(
                    id = getSessionId(session),
                    name = session.sessionName,
                    state = if (session.isPaused) "paused" else "running",
                    isCurrent = session == getCurrentSession(project),
                    runConfigurationName = null,
                    processId = target.info.processId.toLong()
                )
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to attach debugger to Android process: ${e.message}")
        }
    }
}
