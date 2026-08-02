package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.intellij.xdebugger.XDebugSession

/**
 * Steps into the function call on the current line.
 */
class StepIntoTool : AbstractExecutionControlTool() {

    override val name = "step_into"

    override val description = """
        Steps into the function call on the current line, entering the function body.
        Use when you need to debug inside a function. If no function call exists on the current line, behaves like step_over.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Step Into")

    override val action = "step_into"
    override val successMessage = "Stepped into"
    override val newState = "running" // Will pause again after step completes
    override val failurePrefix = "Failed to step into"

    override fun checkPauseState(session: XDebugSession): String? =
        if (!session.isPaused) "Session must be paused to step into" else null

    override fun performAction(session: XDebugSession) = session.stepInto()
}
