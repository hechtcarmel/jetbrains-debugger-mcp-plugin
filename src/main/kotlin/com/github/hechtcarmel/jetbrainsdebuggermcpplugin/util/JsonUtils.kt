package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val mcpJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = false
    isLenient = true
}

val mcpJsonPretty = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = true
    isLenient = true
}

fun buildInputSchema(
    requiredProperties: List<String> = emptyList(),
    block: JsonObjectBuilder.() -> Unit
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject(block))
    if (requiredProperties.isNotEmpty()) {
        put("required", buildJsonArray {
            requiredProperties.forEach { add(JsonPrimitive(it)) }
        })
    }
}

fun JsonObjectBuilder.putJsonObject(key: String, block: JsonObjectBuilder.() -> Unit) {
    put(key, buildJsonObject(block))
}

fun JsonObjectBuilder.putJsonArray(key: String, block: JsonArrayBuilder.() -> Unit) {
    put(key, buildJsonArray(block))
}

fun JsonObjectBuilder.stringProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

fun JsonObjectBuilder.intProperty(name: String, description: String, minimum: Int? = null) {
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
        minimum?.let { put("minimum", it) }
    }
}

fun JsonObjectBuilder.booleanProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }
}

fun JsonObjectBuilder.enumProperty(name: String, description: String, values: List<String>) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        })
    }
}

fun JsonObjectBuilder.projectPathProperty() {
    stringProperty(
        "projectPath",
        "Absolute path to the project root. Required when multiple projects are open."
    )
}

fun JsonObjectBuilder.sessionIdProperty() {
    stringProperty(
        "session_id",
        "Debug session ID. Uses current session if omitted."
    )
}
