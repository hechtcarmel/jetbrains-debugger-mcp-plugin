package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.contract

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionStatus
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackTraceResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariablesResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.WaitForPauseResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts that every tool declaring an `outputSchema` declares the *same* properties its result
 * model actually emits.
 *
 * ## Why this matters more after the SDK migration than before
 *
 * Nothing validates responses today, so a schema that under-declares its payload is invisible:
 * the extra keys simply ride along and clients ignore them. `wait_for_pause` shipped for several
 * releases declaring 10 of the 14 fields `WaitForPauseResult` emits, with no symptom.
 *
 * The MCP specification allows clients to validate `structuredContent` against the declared
 * `outputSchema`, and the official SDK does. Under a strict client, an under-declared schema turns
 * perfectly good responses into protocol errors — so this drift has to be fixed *before* the swap,
 * not discovered during it.
 *
 * The check is a set comparison in both directions: a property declared but never emitted is just
 * as wrong as one emitted but never declared.
 */
@OptIn(ExperimentalSerializationApi::class)
class OutputSchemaFidelityTest {

    private companion object {
        /** Tool name -> the model that tool's `createJsonResult` serializes. */
        val SCHEMA_OWNERS: Map<String, SerialDescriptor> = mapOf(
            "get_variables" to serializer<VariablesResult>().descriptor,
            "get_stack_trace" to serializer<StackTraceResult>().descriptor,
            "get_debug_session_status" to serializer<DebugSessionStatus>().descriptor,
            "wait_for_pause" to serializer<WaitForPauseResult>().descriptor,
        )
    }

    private val registry = ToolRegistry().apply { registerBuiltInTools() }

    @Test
    fun `every declared outputSchema matches the wire keys its model emits`() {
        val drift = SCHEMA_OWNERS.mapNotNull { (toolName, descriptor) ->
            val tool = requireNotNull(registry.getTool(toolName)) { "$toolName is not registered" }
            val schema = requireNotNull(tool.outputSchema) { "$toolName no longer declares an outputSchema" }

            val declared = schema["properties"]!!.jsonObject.keys
            val emitted = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

            val undeclared = (emitted - declared).sorted()
            val phantom = (declared - emitted).sorted()
            if (undeclared.isEmpty() && phantom.isEmpty()) return@mapNotNull null

            buildString {
                append("$toolName vs ${descriptor.serialName.substringAfterLast('.')}:")
                if (undeclared.isNotEmpty()) append(" emitted-but-not-declared=$undeclared")
                if (phantom.isNotEmpty()) append(" declared-but-never-emitted=$phantom")
            }
        }

        assertEquals(
            "outputSchema drift. A strict MCP client validates structuredContent against these " +
                "schemas, so every mismatch below turns a valid response into a protocol error.",
            emptyList<String>(),
            drift
        )
    }

    /**
     * The set of outputSchema-declaring tools is itself pinned, so adding a schema to a fifth tool
     * without adding it here cannot silently escape the fidelity check above.
     */
    @Test
    fun `the set of tools declaring an outputSchema is pinned`() {
        val declaring = registry.getAllTools()
            .filter { it.outputSchema != null }
            .map { it.name }
            .sorted()

        assertEquals(
            "A tool gained or lost an outputSchema. Add it to SCHEMA_OWNERS with the model it " +
                "serializes so its schema stays honest.",
            SCHEMA_OWNERS.keys.sorted(),
            declaring
        )
    }

    /**
     * `required` must be a subset of `properties`: naming a required property that the schema does
     * not define is invalid JSON Schema and rejected outright by strict validators.
     */
    @Test
    fun `required lists only properties the schema defines`() {
        SCHEMA_OWNERS.keys.forEach { toolName ->
            val schema = registry.getTool(toolName)!!.outputSchema!!
            val properties = schema["properties"]!!.jsonObject.keys
            val required = schema["required"]?.let { req ->
                (req as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive().content }.orEmpty()
            }.orEmpty()

            assertTrue(
                "$toolName marks ${required - properties} as required but never defines them",
                properties.containsAll(required)
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive() =
        this as kotlinx.serialization.json.JsonPrimitive
}
