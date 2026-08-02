package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts a plugin [McpTool] into the SDK's wire [Tool].
 *
 * Tools author their schemas as raw JSON Schema `JsonObject`s, which is the readable format and the
 * one every tool already uses. This is the single place that translates that authoring format into
 * the SDK's [ToolSchema].
 *
 * ## The one lossy step
 *
 * [ToolSchema] models exactly `{properties, required, $defs, type}`. Every key the plugin authors
 * outside that set is dropped — in practice that is `additionalProperties: false`, present on all
 * 23 input schemas, which means unknown arguments are no longer rejected by a validating client.
 *
 * That loss is deliberate and documented (design doc, breaking change B1). It is enforced in two
 * directions: `McpSdkAssumptionsTest` fails if a future SDK *gains* the ability to express it, and
 * `ToolManifestContractTest` snapshots the mapped [Tool] rather than the authored schema, so the
 * loss is visible in the golden file instead of hiding behind it.
 */
fun McpTool.toSdkTool(): Tool = Tool(
    name = name,
    description = description,
    inputSchema = inputSchema.toToolSchema(),
    outputSchema = outputSchema?.toToolSchema(),
    annotations = annotations,
)

private fun JsonObject.toToolSchema(): ToolSchema = ToolSchema(
    properties = this["properties"]?.jsonObject ?: JsonObject(emptyMap()),
    required = (this["required"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
    defs = this["\$defs"]?.jsonObject,
)
