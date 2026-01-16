package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Run configuration information.
 *
 * Returned by list_run_configurations tool.
 */
@Serializable
data class RunConfigurationInfo(
    val name: String,
    val type: String,
    val typeId: String,
    val isTemporary: Boolean = false,
    val canRun: Boolean = true,
    val canDebug: Boolean = true,
    val folder: String? = null,
    val description: String? = null
)

/**
 * Result of list_run_configurations tool.
 */
@Serializable
data class RunConfigurationListResult(
    val configurations: List<RunConfigurationInfo>,
    val activeConfiguration: String? = null
)

/**
 * Result of execution control tools (resume, pause, step_over, etc.)
 */
@Serializable
data class ExecutionControlResult(
    val sessionId: String,
    val action: String,
    val status: String,
    val message: String,
    val newState: String? = null
)

/**
 * Result of stop_debug_session tool.
 */
@Serializable
data class StopSessionResult(
    val sessionId: String,
    val status: String,
    val message: String
)

@Serializable
data class RunLogResult(
    val sessionId: String,
    val log: String,
    val totalLines: Int,
    val returnedLines: Int
)