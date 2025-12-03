package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Breakpoint information.
 *
 * Returned by list_breakpoints and set_breakpoint tools.
 */
@Serializable
data class BreakpointInfo(
    val id: String,
    val type: String,
    val file: String? = null,
    val line: Int? = null,
    val enabled: Boolean = true,
    val condition: String? = null,
    val logMessage: String? = null,
    val suspendPolicy: String? = null,
    val hitCount: Int = 0,
    val temporary: Boolean = false,
    val exceptionClass: String? = null,
    val caught: Boolean? = null,
    val uncaught: Boolean? = null
)

/**
 * Result of setting a breakpoint.
 *
 * Returned by set_breakpoint tool.
 */
@Serializable
data class SetBreakpointResult(
    val breakpointId: String,
    val status: String,
    val verified: Boolean,
    val message: String,
    val file: String? = null,
    val line: Int? = null
)

/**
 * Result of removing a breakpoint.
 *
 * Returned by remove_breakpoint tool.
 */
@Serializable
data class RemoveBreakpointResult(
    val breakpointId: String,
    val status: String,
    val message: String
)
