package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Variable information.
 *
 * Returned by get_variables and expand_variable tools.
 */
@Serializable
data class VariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val hasChildren: Boolean = false,
    val id: String? = null,
    val scope: String? = null,
    val declaredType: String? = null,
    val isStatic: Boolean = false
)

/**
 * Watch expression information.
 *
 * Returned as part of DebugSessionStatus and by watch-related tools.
 */
@Serializable
data class WatchInfo(
    val id: String,
    val expression: String,
    val value: String? = null,
    val type: String? = null,
    val hasChildren: Boolean = false,
    val error: String? = null
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
 * Result of expand_variable tool.
 */
@Serializable
data class ExpandVariableResult(
    val sessionId: String,
    val variableId: String,
    val name: String,
    val children: List<VariableInfo>,
    val hasMore: Boolean = false
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

/**
 * Result of add_watch tool.
 */
@Serializable
data class AddWatchResult(
    val sessionId: String,
    val watch: WatchInfo,
    val message: String
)

/**
 * Result of remove_watch tool.
 */
@Serializable
data class RemoveWatchResult(
    val sessionId: String,
    val watchId: String,
    val message: String
)
