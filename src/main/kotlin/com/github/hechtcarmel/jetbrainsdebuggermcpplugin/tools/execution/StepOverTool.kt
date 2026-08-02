package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.intellij.xdebugger.XDebugSession

/**
 * Steps over the current line (executes it without entering functions).
 */
class StepOverTool : AbstractExecutionControlTool() {

    override val name = "step_over"

    override val description = """
        Executes the current line and stops at the next line, without entering function calls.
        Use for line-by-line debugging when you don't need to inspect function internals. Check get_debug_session_status for the new location.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Step Over")

    override val action = "step_over"
    override val successMessage = "Stepped over"
    override val newState = "running" // Will pause again after step completes
    override val failurePrefix = "Failed to step over"

    override fun checkPauseState(session: XDebugSession): String? =
        if (!session.isPaused) "Session must be paused to step over" else null

    override fun performAction(session: XDebugSession) = session.stepOver(false)
}
