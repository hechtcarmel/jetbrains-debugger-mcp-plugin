package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.*

class ListRunSessionsTool : AbstractMcpTool() {
    override val name = "list_run_sessions"
    override val description = "Lists ordinary Run executions observed by this plugin, including starting, running, stopping, failed_start, terminated and unavailable records. IDs start with run: and cannot be used with Debug tools. Metadata is limited to 128 sessions per project; oldest finished records are evicted first. Console history remains owned by the IDE; closing/reusing tabs or clearing/cyclic history can remove it. Read and stop operations never fall back to another session."
    override val annotations = ToolAnnotationPresets.readOnly("List Run Sessions")
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { val (key, schema) = projectPathProperty(); put(key, schema) }
    }
    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult =
        createJsonResult(onEdt { RunSessionService.getInstance(project).list() })
}

class GetRunConsoleOutputTool : AbstractMcpTool() {
    override val name = "get_run_console_output"
    override val description = "Reads only the exact ordinary Run session_id returned by execute_run_configuration(mode=run) or list_run_sessions. Returns session lifecycle/exit/failure diagnostics plus a bounded console page, or consoleError when no retained readable text is available. Failed or terminated sessions do not imply successful startup. Never launches, stops, pauses, flushes or changes console focus. lines defaults to 100 (max 1000), offset skips newest retained lines, max_chars defaults to 20000 (max 100000 UTF-16 characters). Pages keep their end when truncated. Line positions are relative to current IDE history, not durable cursors; cleared/evicted output is unrecoverable."
    override val annotations = ToolAnnotationPresets.readOnly("Get Run Console Output")
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (key, schema) = projectPathProperty(); put(key, schema)
            put("session_id", stringProperty("Required exact run: session ID; Debug IDs are rejected."))
            put("lines", integerProperty("Maximum retained lines returned.", 100, 1, 1000))
            put("offset", integerProperty("Newest retained lines to skip for backward pagination.", 0, 0))
            put("max_chars", integerProperty("Maximum UTF-16 characters; retain the page end when truncated.", 20000, 1, 100000))
        }
        putJsonArray("required") { add(JsonPrimitive("session_id")) }
    }
    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val id = ToolArguments.requireString(arguments, "session_id")
        val lines = ToolArguments.optionalInt(arguments, "lines", 100, 1, 1000)
        val offset = ToolArguments.optionalInt(arguments, "offset", 0, 0)
        val maxChars = ToolArguments.optionalInt(arguments, "max_chars", 20000, 1, 100000)
        return createJsonResult(onEdt { RunSessionService.getInstance(project).read(id, lines, offset, maxChars) })
    }
}

class StopRunSessionTool : AbstractMcpTool() {
    override val name = "stop_run_session"
    override val description = "Requests termination of only the exact ordinary Run session_id. Requires a run: ID; rejects Debug IDs. Does not search by name, PID or focused tab. Sessions without a live handler return not_running. A stopping response is a request, not confirmation of process exit; inspect list_run_sessions for completion."
    override val annotations = ToolAnnotationPresets.mutable("Stop Run Session")
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (key, schema) = projectPathProperty(); put(key, schema)
            put("session_id", stringProperty("Required exact run: session ID."))
        }
        putJsonArray("required") { add(JsonPrimitive("session_id")) }
    }
    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val id = ToolArguments.requireString(arguments, "session_id")
        return createJsonResult(onEdt { RunSessionService.getInstance(project).stop(id) })
    }
}
