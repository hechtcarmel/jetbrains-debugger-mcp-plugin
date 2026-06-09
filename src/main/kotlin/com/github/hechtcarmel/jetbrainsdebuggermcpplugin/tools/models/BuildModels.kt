package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Represents a single build message (error, warning, or info).
 */
@Serializable
data class BuildMessage(
    val severity: String,  // "ERROR", "WARNING", "INFO"
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null
)

/**
 * Result of get_build_status tool.
 */
@Serializable
data class BuildStatusResult(
    val isBuilding: Boolean,
    val lastBuildSuccessful: Boolean? = null,
    val lastBuildTime: String? = null,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val status: String,  // "idle", "building", "success", "failed", "cancelled"
    val message: String
)

/**
 * Result of get_build_output tool.
 */
@Serializable
data class BuildOutputResult(
    val hasOutput: Boolean,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val messages: List<BuildMessage> = emptyList(),
    val rawOutput: String? = null,
    val buildLogPath: String? = null,
    val status: String,
    val message: String
)

/**
 * Result of start_build tool.
 */
@Serializable
data class StartBuildResult(
    val status: String,  // "started", "already_building", "failed"
    val message: String
)
