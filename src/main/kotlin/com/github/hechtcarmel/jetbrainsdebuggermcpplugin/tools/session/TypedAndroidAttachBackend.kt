package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.Client
import com.android.ddmlib.ClientData
import com.android.tools.idea.adb.AdbService
import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.debug.AndroidDebuggerState
import com.android.tools.idea.execution.common.debug.DebugSessionStarter
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession

/**
 * Loaded through the optional Android descriptor only when the Android plugin is available.
 *
 * API owners (source filenames and packages for locating them when modules move):
 * - https://android.googlesource.com/platform/tools/base/ —
 *   ddmlib/src/main/java/com/android/ddmlib/{AndroidDebugBridge,IDevice,Client,ClientData}.java
 * - https://android.googlesource.com/platform/tools/adt/idea/ —
 *   com.android.tools.idea.adb.AdbService (AdbService.java) and
 *   com.android.tools.idea.execution.common.debug.{AndroidDebugger.java,DebugSessionStarter.kt}.
 *
 * DebugSessionStarter.attachDebuggerToClientAndShowTab is Android's selected-client attach
 * path. It returns the exact session and dispatches session creation to Android's UI dispatcher.
 * Compilation checks its API signature; the core boundary handles incompatible installed APIs.
 */
internal class TypedAndroidAttachBackend internal constructor(
    private val bridgeProvider: suspend (Project) -> AndroidDebugBridge,
    private val debuggerProvider: () -> List<AndroidDebugger<*>>,
    private val sessionStarter: AndroidSessionStarter
) : AndroidAttachBackend {
    constructor() : this(::debugBridge, { AndroidDebugger.EP_NAME.extensionList }, DefaultAndroidSessionStarter)

    private data class ClientHandle(val client: Client) : AndroidProcessHandle

    override suspend fun processes(project: Project): List<AndroidProcessCandidate> {
        val bridge = bridgeProvider(project)
        if (!bridge.isConnected || !bridge.hasInitialDeviceList()) {
            throw ToolExecutionError("Android debug bridge is not ready. Wait for device discovery and retry.")
        }
        return bridge.devices.filterNotNull().filter { it.isOnline }.flatMap { device ->
            device.clients.filterNotNull().filter { it.isValid }.map { client ->
                val data = client.clientData
                AndroidProcessCandidate(
                    AndroidProcessInfo(device.serialNumber, data.pid, processName(data), client.isDebuggerAttached),
                    ClientHandle(client)
                )
            }
        }.sortedWith(compareBy({ it.info.deviceSerial }, { it.info.processId }))
    }

    override suspend fun attach(project: Project, process: AndroidProcessCandidate, debuggerId: String): XDebugSession {
        val client = (process.handle as? ClientHandle)?.client
            ?: throw ToolExecutionError("Android process handle does not belong to this backend")
        val supported = debuggerProvider().filter { it.supportsProject(project) }
        val debugger = supported.singleOrNull { it.id == debuggerId }
            ?: throw ToolExecutionError(
                "Android debugger not available: $debuggerId. Available debugger IDs: " + supported.joinToString { it.id }
            )
        val existing = debugger.getExistingDebugSession(project, client)
        if (existing != null && !existing.isStopped) {
            if (existing.project != project) throw ToolExecutionError("Android process is already being debugged in another project")
            return existing
        }
        if (process.info.debuggerAttached) throw ToolExecutionError("Android process already has a debugger attached")
        return startSession(project, client, debugger)
    }

    private suspend fun <S : AndroidDebuggerState> startSession(
        project: Project, client: Client, debugger: AndroidDebugger<S>
    ): XDebugSession = sessionStarter.attach(project, client, debugger, debugger.createState())
}

internal fun processName(data: ClientData): String? = try {
    data.processName
} catch (_: NoSuchMethodError) {
    // Older ddmlib exposes the process name through getClientDescription instead.
    @Suppress("DEPRECATION")
    data.clientDescription
}

private suspend fun debugBridge(project: Project): AndroidDebugBridge {
    val current = AndroidDebugBridge.getBridge()
    if (current != null && current.isConnected) return current
    return awaitBridge(AdbService.getInstance().getDebugBridge(project))
        ?: throw ToolExecutionError("Android debug bridge initialization returned no bridge")
}

internal interface AndroidSessionStarter {
    suspend fun <S : AndroidDebuggerState> attach(
        project: Project, client: Client, debugger: AndroidDebugger<S>, state: S
    ): XDebugSession
}

private object DefaultAndroidSessionStarter : AndroidSessionStarter {
    override suspend fun <S : AndroidDebuggerState> attach(
        project: Project, client: Client, debugger: AndroidDebugger<S>, state: S
    ): XDebugSession = DebugSessionStarter.attachDebuggerToClientAndShowTab(project, client, debugger, state)
}
