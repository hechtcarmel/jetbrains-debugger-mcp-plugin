package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.intellij.xdebugger.XDebugSession

/**
 * Resumes execution of a paused debug session.
 */
class ResumeTool : AbstractExecutionControlTool() {

    override val name = "resume_execution"

    override val description = """
        Resumes program execution from a paused state.
        Execution continues until the next breakpoint, exception, or program completion. Use get_debug_session_status afterward to see where execution stopped.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Resume Execution")

    override val action = "resume"
    override val successMessage = "Execution resumed"
    override val newState = "running"
    override val failurePrefix = "Failed to resume"

    override fun checkPauseState(session: XDebugSession): String? =
        if (!session.isPaused) "Session is not paused" else null

    override fun performAction(session: XDebugSession) = session.resume()
}
