package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.intellij.xdebugger.XDebugSession

/**
 * Pauses execution of a running debug session.
 */
class PauseTool : AbstractExecutionControlTool() {

    override val name = "pause_execution"

    override val description = """
        Pauses a running debug session at its current execution point.
        Use when you need to inspect state during execution. After pausing, use get_debug_session_status to see the current location.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.idempotentMutable("Pause Execution")

    override val action = "pause"
    override val successMessage = "Execution paused"
    override val newState = "paused"
    override val failurePrefix = "Failed to pause"

    override fun checkPauseState(session: XDebugSession): String? =
        if (session.isPaused) "Session is already paused" else null

    override fun performAction(session: XDebugSession) = session.pause()
}
