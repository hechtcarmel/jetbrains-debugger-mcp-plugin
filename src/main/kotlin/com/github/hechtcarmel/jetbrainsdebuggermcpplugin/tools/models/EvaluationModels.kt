package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Result of expression evaluation.
 *
 * Returned by the evaluate tool.
 */
@Serializable
data class EvaluationResult(
    val expression: String,
    val value: String,
    val type: String,
    val hasChildren: Boolean = false,
    val id: String? = null,
    val error: String? = null
)

/**
 * Full evaluation response including session context.
 */
@Serializable
data class EvaluateResponse(
    val sessionId: String,
    val frameIndex: Int,
    val result: EvaluationResult
)
