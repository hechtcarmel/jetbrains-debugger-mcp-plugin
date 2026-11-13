package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

@Serializable
data class RunSessionInfo(
    val id: String,
    val configurationName: String,
    val state: String,
    val exitCode: Int? = null,
    val failureMessage: String? = null,
    val consoleAvailable: Boolean = false,
    val mode: String = "run"
)

@Serializable
data class RunSessionListResult(
    val sessions: List<RunSessionInfo>,
    val trackingLimitReached: Boolean,
    val maxSessions: Int
)

@Serializable
data class RunConsoleOutputResult(
    val session: RunSessionInfo,
    val console: ConsoleOutputResult? = null,
    val consoleError: String? = null
)
