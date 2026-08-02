package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

/**
 * The three annotation shapes this plugin's tools actually use.
 *
 * [ToolAnnotations] is an SDK type, but these presets are not — the SDK has no opinion about which
 * combinations are meaningful, and spelling all five hints out at 23 call sites invites exactly the
 * kind of drift where an inspection tool quietly claims `destructiveHint = true`.
 *
 * `openWorldHint` is `false` throughout: every tool acts on the local IDE, never on an open-ended
 * external world.
 */
object ToolAnnotationPresets {

    /** Inspection tools — they read IDE state and never change it. */
    fun readOnly(title: String) = ToolAnnotations(
        title = title,
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

    /** State-changing tools where calling twice is not the same as calling once (e.g. stepping). */
    fun mutable(title: String, destructive: Boolean = false) = ToolAnnotations(
        title = title,
        readOnlyHint = false,
        destructiveHint = destructive,
        idempotentHint = false,
        openWorldHint = false,
    )

    /** State-changing tools that converge on the same state however often they are called. */
    fun idempotentMutable(title: String, destructive: Boolean = false) = ToolAnnotations(
        title = title,
        readOnlyHint = false,
        destructiveHint = destructive,
        idempotentHint = true,
        openWorldHint = false,
    )
}
