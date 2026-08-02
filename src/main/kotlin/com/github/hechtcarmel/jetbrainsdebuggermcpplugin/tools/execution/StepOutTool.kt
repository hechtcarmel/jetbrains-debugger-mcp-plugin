package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.intellij.xdebugger.XDebugSession

class StepOutTool : AbstractExecutionControlTool() {

    override val name = "step_out"

    override val description = """
        Continues execution until the current function returns, then pauses at the caller.
        Use to exit a function you've stepped into without stepping through every remaining line.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Step Out")

    override val action = "step_out"
    override val successMessage = "Stepping out of current method"
    override val newState = "running"
    override val failurePrefix = "Failed to step out"

    override fun checkPauseState(session: XDebugSession): String? =
        if (!session.isPaused) "Session must be paused to step out" else null

    override fun performAction(session: XDebugSession) = session.stepOut()
}
