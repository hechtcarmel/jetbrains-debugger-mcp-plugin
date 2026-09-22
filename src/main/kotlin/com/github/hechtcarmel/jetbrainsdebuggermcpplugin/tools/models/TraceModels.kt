package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * One stop recorded by trace_execution, with the probe's expressions evaluated there.
 */
@Serializable
data class TraceHit(
    val hit: Int,
    val file: String,
    val line: Int,
    val presentation: String? = null,
    /** Expression text to the value it had at this stop. Failures are recorded, not dropped. */
    val values: Map<String, String>
)

/**
 * The transcript produced by a single trace_execution call.
 *
 * Returned whole: the point of the tool is that one round trip answers a question that the
 * interactive tools answer in one round trip per stop.
 */
@Serializable
data class TraceExecutionResult(
    /** 'completed' when every probe met its budget, 'finished' when the process exited first, 'timeout' otherwise. */
    val status: String,
    val message: String,
    val hits: List<TraceHit>,
    /** Probes that never fired, so an empty transcript is distinguishable from a mis-placed probe. */
    val probesNeverHit: List<String> = emptyList()
)
