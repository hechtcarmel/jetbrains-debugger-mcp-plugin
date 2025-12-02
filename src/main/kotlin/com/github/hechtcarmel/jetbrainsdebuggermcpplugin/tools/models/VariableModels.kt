package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Variable information.
 *
 * Returned by get_variables tool.
 */
@Serializable
data class VariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val hasChildren: Boolean = false,
    val scope: String? = null,
    val declaredType: String? = null,
    val isStatic: Boolean = false
)

/**
 * Result of get_variables tool.
 */
@Serializable
data class VariablesResult(
    val sessionId: String,
    val frameIndex: Int,
    val variables: List<VariableInfo>,
    val scope: String? = null
)

/**
 * Result of set_variable tool.
 */
@Serializable
data class SetVariableResult(
    val sessionId: String,
    val variableName: String,
    val oldValue: String,
    val newValue: String,
    val type: String,
    val message: String
)
