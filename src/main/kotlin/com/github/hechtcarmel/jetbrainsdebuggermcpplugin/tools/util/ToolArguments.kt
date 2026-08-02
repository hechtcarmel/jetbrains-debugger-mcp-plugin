package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Validates tool arguments at the boundary, before any tool logic runs.
 *
 * The MCP SDK's `ToolSchema` cannot express `additionalProperties: false` and performs no input
 * validation of its own (see CLAUDE.md "Known gaps"), so every argument arrives as raw JSON. Until
 * this helper, a wrong-typed value either coerced silently (`suspend_policy: "banana"` became
 * `ALL`), produced a misleading "Missing required parameter" (unparseable `line`), or escaped as a
 * raw exception (`frame_index: -1`).
 *
 * ## Failure classes and their messages
 *
 * - absent / JSON `null`  -> `Missing required parameter: <name>` (pinned pre-existing string)
 * - wrong JSON kind        -> `Invalid type for parameter: <name> (expected <type>)`
 * - out of range / not in enum -> `Invalid value for parameter: <name> (<constraint>)`
 *
 * All failures throw [ToolExecutionError], which [com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool.execute]
 * converts to the standard `isError: true` result.
 *
 * Typing is strict, matching the declared schemas: `"42"` is not an integer and `42` is not a
 * string. Enum values are the one lenient spot — they are lowercased before matching, preserving
 * the tolerance the old `suspend_policy.lowercase()` code had for `"None"`.
 */
object ToolArguments {

    fun requireString(arguments: JsonObject, name: String): String =
        optionalString(arguments, name) ?: throw missing(name)

    fun optionalString(arguments: JsonObject, name: String): String? {
        val primitive = primitiveOrNull(arguments, name, expected = "string") ?: return null
        if (!primitive.isString) throw invalidType(name, "string")
        return primitive.content
    }

    fun requireInt(arguments: JsonObject, name: String, min: Int? = null, max: Int? = null): Int =
        optionalIntOrNull(arguments, name, min, max) ?: throw missing(name)

    fun optionalInt(arguments: JsonObject, name: String, default: Int, min: Int? = null, max: Int? = null): Int =
        optionalIntOrNull(arguments, name, min, max) ?: default

    fun optionalIntOrNull(arguments: JsonObject, name: String, min: Int? = null, max: Int? = null): Int? {
        val primitive = primitiveOrNull(arguments, name, expected = "integer") ?: return null
        if (primitive.isString) throw invalidType(name, "integer")
        val value = primitive.intOrNull ?: throw invalidType(name, "integer")
        if ((min != null && value < min) || (max != null && value > max)) {
            throw ToolExecutionError("Invalid value for parameter: $name (${rangeConstraint(min, max)})")
        }
        return value
    }

    fun optionalBoolean(arguments: JsonObject, name: String, default: Boolean): Boolean {
        val primitive = primitiveOrNull(arguments, name, expected = "boolean") ?: return default
        if (primitive.isString) throw invalidType(name, "boolean")
        return primitive.booleanOrNull ?: throw invalidType(name, "boolean")
    }

    /** Matches case-insensitively and returns the canonical (lowercase) value, or null when absent. */
    fun optionalEnum(arguments: JsonObject, name: String, allowed: List<String>): String? {
        val value = optionalString(arguments, name)?.lowercase() ?: return null
        if (value !in allowed) {
            throw ToolExecutionError(
                "Invalid value for parameter: $name (must be one of: ${allowed.joinToString(", ")})"
            )
        }
        return value
    }

    fun optionalStringList(arguments: JsonObject, name: String): List<String>? {
        val element = arguments[name] ?: return null
        if (element is JsonNull) return null
        val array = element as? JsonArray ?: throw invalidType(name, "array of strings")
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: throw invalidType(name, "array of strings")
            if (!primitive.isString) throw invalidType(name, "array of strings")
            primitive.content
        }
    }

    /** Absent and explicit JSON `null` are both "not provided" — never the string "null". */
    private fun primitiveOrNull(arguments: JsonObject, name: String, expected: String): JsonPrimitive? {
        val element = arguments[name] ?: return null
        if (element is JsonNull) return null
        return element as? JsonPrimitive ?: throw invalidType(name, expected)
    }

    private fun missing(name: String) = ToolExecutionError("Missing required parameter: $name")

    private fun invalidType(name: String, expected: String) =
        ToolExecutionError("Invalid type for parameter: $name (expected $expected)")

    private fun rangeConstraint(min: Int?, max: Int?): String = when {
        min != null && max != null -> "must be between $min and $max"
        min != null -> "must be >= $min"
        else -> "must be <= $max"
    }
}
