package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The session-status property definitions shared verbatim by the `get_debug_session_status` and
 * `wait_for_pause` output schemas (`wait_for_pause` returns the full status plus its own
 * `waitResult`/`message` envelope).
 *
 * Extracted from two hand-maintained ~85-line copies. The emitted JSON is pinned byte-for-byte by
 * `ToolManifestContractTest`, so a change here is a deliberate, reviewed contract change for both
 * tools at once instead of a drift between them.
 *
 * The `sessionId`/`name`/`state` properties stay tool-local: the two tools describe them with
 * different (pinned) wording.
 */
internal fun JsonObjectBuilder.putSessionStatusProperties() {
    putJsonObject("pausedReason") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }; put("description", "Why execution paused: 'breakpoint', 'step', 'exception', or 'pause'") }
    putJsonObject("currentLocation") {
        putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
        putJsonObject("properties") {
            putJsonObject("file") { put("type", "string") }
            putJsonObject("line") { put("type", "integer") }
            putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
            putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
        }
        put("description", "Current execution location")
    }
    putJsonObject("variables") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("value") { put("type", "string") }
                putJsonObject("type") { put("type", "string") }
                putJsonObject("hasChildren") { put("type", "boolean") }
            }
        }
        put("description", "Variables visible in current stack frame")
    }
    putJsonObject("stackSummary") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("index") { put("type", "integer") }
                putJsonObject("file") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                putJsonObject("line") { putJsonArray("type") { add(JsonPrimitive("integer")); add(JsonPrimitive("null")) } }
                putJsonObject("className") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                putJsonObject("methodName") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
            }
        }
        put("description", "Stack trace summary")
    }
    putJsonObject("sourceContext") {
        putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
        put("description", "Source code around the current execution point")
    }
    putJsonObject("breakpointHit") {
        putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
        putJsonObject("properties") {
            putJsonObject("breakpointId") { put("type", "string") }
            putJsonObject("type") { put("type", "string") }
            putJsonObject("file") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
            putJsonObject("line") { putJsonArray("type") { add(JsonPrimitive("integer")); add(JsonPrimitive("null")) } }
            putJsonObject("condition") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
            putJsonObject("hitCount") { put("type", "integer") }
        }
        put("description", "The breakpoint at the current location, when the pause was caused by one")
    }
    putJsonObject("totalStackDepth") {
        put("type", "integer")
        put("description", "Number of frames reported in stackSummary")
    }
    putJsonObject("currentThread") {
        putJsonArray("type") { add(JsonPrimitive("object")); add(JsonPrimitive("null")) }
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("state") { put("type", "string") }
            putJsonObject("isCurrent") { put("type", "boolean") }
            putJsonObject("group") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
            putJsonObject("frameCount") { putJsonArray("type") { add(JsonPrimitive("integer")); add(JsonPrimitive("null")) } }
        }
        put("description", "The thread the debugger is currently focused on")
    }
    putJsonObject("threadCount") {
        put("type", "integer")
        put("description", "Number of threads reported for the session")
    }
}
