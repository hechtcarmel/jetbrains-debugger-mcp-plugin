package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class AndroidProcessInfo(
    val deviceSerial: String,
    val processId: Int,
    val processName: String?,
    val debuggerAttached: Boolean
)

@Serializable
data class AndroidProcessListResult(val processes: List<AndroidProcessInfo>)

internal data class AndroidProcessSelector(val deviceSerial: String?, val processId: Int?, val processName: String?) {
    fun select(processes: List<AndroidProcessInfo>): AndroidProcessInfo {
        val matches = processes.filter {
            (deviceSerial == null || it.deviceSerial == deviceSerial) &&
                (processId == null || it.processId == processId) &&
                (processName == null || it.processName == processName)
        }
        return matches.singleOrNull() ?: throw ToolExecutionError(
            if (matches.isEmpty()) {
                "No matching Android process. Available processes: ${Json.encodeToString(processes)}"
            } else {
                "Multiple matching Android processes. Specify device_serial and process_id. " +
                    "Matching processes: ${Json.encodeToString(matches)}"
            }
        )
    }

    companion object {
        fun from(arguments: JsonObject): AndroidProcessSelector {
            val device = nonBlank(arguments, "device_serial")
            val pid = ToolArguments.optionalIntOrNull(arguments, "process_id", min = 1)
            val name = nonBlank(arguments, "process_name")
            nonBlank(arguments, "debugger_id")
            if (pid == null && name == null) {
                throw ToolExecutionError("Android attachment requires process_id or process_name")
            }
            return AndroidProcessSelector(device, pid, name)
        }

        fun nonBlank(arguments: JsonObject, name: String): String? =
            ToolArguments.optionalString(arguments, name)?.also {
                if (it.isBlank()) throw ToolExecutionError("Invalid value for parameter: $name (must not be blank)")
            }
    }
}
