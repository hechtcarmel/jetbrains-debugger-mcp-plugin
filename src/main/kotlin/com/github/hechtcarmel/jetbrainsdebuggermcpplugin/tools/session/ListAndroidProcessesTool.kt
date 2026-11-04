package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
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

class ListAndroidProcessesTool internal constructor(
    private val backend: AndroidAttachBackend? = null
) : AbstractMcpTool() {
    override val name = "list_android_processes"
    override val description = """
        Lists running Android processes visible to the IDE debugger, with device serial, PID, process name and attachment state.
        Use process_name or process_id and device_serial with attach_android_debugger to attach to a running Android app without installing or launching it or opening the process chooser. Requires the Android plugin and a connected Android device or emulator.
    """.trimIndent()
    override val annotations = ToolAnnotationPresets.readOnly("List Android Processes")
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (key, schema) = projectPathProperty()
            put(key, schema)
            put("device_serial", stringProperty("Optional exact Android device serial to filter the process list"))
        }
        putJsonArray("required") { }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val device = AndroidProcessSelector.nonBlank(arguments, "device_serial")
        return try {
            val processes = withContext(Dispatchers.IO) { withAndroidBackend(backend) { it.processes(project) } }
                .map { it.info }.filter { device == null || it.deviceSerial == device }
            createJsonResult(AndroidProcessListResult(processes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to list Android processes: ${e.message}")
        }
    }
}
