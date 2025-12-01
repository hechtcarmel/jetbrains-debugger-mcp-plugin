package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Basic debug session information.
 *
 * Returned by list_debug_sessions and start_debug_session tools.
 */
@Serializable
data class DebugSessionInfo(
    val id: String,
    val name: String,
    val state: String,
    val isCurrent: Boolean,
    val runConfigurationName: String? = null,
    val processId: Long? = null
)

/**
 * Comprehensive debug session status.
 *
 * Returned by get_debug_session_status tool. Provides a complete view of the
 * debug state in a single call, reducing round-trips for AI agents.
 */
@Serializable
data class DebugSessionStatus(
    val sessionId: String,
    val name: String,
    val state: String,
    val pausedReason: String? = null,
    val currentLocation: SourceLocation? = null,
    val breakpointHit: BreakpointHitInfo? = null,
    val stackSummary: List<StackFrameInfo> = emptyList(),
    val totalStackDepth: Int = 0,
    val variables: List<VariableInfo> = emptyList(),
    val watches: List<WatchInfo> = emptyList(),
    val sourceContext: SourceContext? = null,
    val currentThread: ThreadInfo? = null,
    val threadCount: Int = 0
)

/**
 * Information about a breakpoint that was hit.
 *
 * Included in DebugSessionStatus when paused at a breakpoint.
 */
@Serializable
data class BreakpointHitInfo(
    val breakpointId: String,
    val type: String,
    val file: String? = null,
    val line: Int? = null,
    val condition: String? = null,
    val hitCount: Int = 0
)

/**
 * Source code location.
 *
 * Used to identify the current execution position in the debugger.
 */
@Serializable
data class SourceLocation(
    val file: String,
    val line: Int,
    val className: String? = null,
    val methodName: String? = null,
    val signature: String? = null
)

/**
 * Source code context around the current execution line.
 *
 * Returned by get_source_context tool and optionally in DebugSessionStatus.
 */
@Serializable
data class SourceContext(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val currentLine: Int,
    val lines: List<SourceLine>,
    val breakpointsInView: List<Int> = emptyList()
)

/**
 * A single line of source code.
 */
@Serializable
data class SourceLine(
    val number: Int,
    val content: String,
    val isCurrent: Boolean = false
)
