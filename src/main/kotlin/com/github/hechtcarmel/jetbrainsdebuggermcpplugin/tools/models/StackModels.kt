package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Stack frame information.
 *
 * Returned by get_stack_trace and as part of DebugSessionStatus.
 */
@Serializable
data class StackFrameInfo(
    val index: Int,
    val file: String? = null,
    val line: Int? = null,
    val className: String? = null,
    val methodName: String? = null,
    val isCurrent: Boolean = false,
    val isLibrary: Boolean = false,
    val presentation: String? = null
)

/**
 * Thread information.
 *
 * Returned by list_threads tool and as part of DebugSessionStatus.
 */
@Serializable
data class ThreadInfo(
    val id: String,
    val name: String,
    val state: String,
    val isCurrent: Boolean = false,
    val group: String? = null,
    val frameCount: Int? = null
)

/**
 * Result of get_stack_trace tool.
 */
@Serializable
data class StackTraceResult(
    val sessionId: String,
    val threadId: String? = null,
    val frames: List<StackFrameInfo>,
    val totalFrames: Int
)

/**
 * Result of list_threads tool.
 */
@Serializable
data class ThreadListResult(
    val sessionId: String,
    val threads: List<ThreadInfo>,
    val currentThreadId: String? = null
)

/**
 * Result of select_stack_frame tool.
 */
@Serializable
data class SelectFrameResult(
    val sessionId: String,
    val frameIndex: Int,
    val frame: StackFrameInfo,
    val message: String
)
