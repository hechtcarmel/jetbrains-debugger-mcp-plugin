package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.contract

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden snapshot of the MCP tool INPUT surface: every registered tool's name, description,
 * complete input schema, output schema and annotations.
 *
 * This is the regression net for the MCP Kotlin SDK migration. A single assertion covers every
 * tool, every schema property, every type, every enum, every default and every `required` array —
 * so deleting a `register(...)` call, renaming a parameter, flipping a type or emptying a
 * description all fail here rather than silently shipping to clients.
 *
 * Two things it deliberately does NOT cover:
 * - Response shapes, which are pinned by [ResultShapeContractTest].
 * - Tool *behaviour*. A tool can keep a byte-identical schema and still do the wrong thing.
 *
 * Tools are sorted by name because [ToolRegistry] stores them in a `ConcurrentHashMap`
 * (ToolRegistry.kt:30) — snapshotting iteration order would pass locally and flake in CI.
 *
 * The manifest is a *contract with MCP clients*. Regenerate deliberately with:
 * ```
 * ./gradlew test --tests "*ToolManifestContractTest" -Dcontract.update=true
 * ```
 */
class ToolManifestContractTest {

    private companion object {
        const val GOLDEN_RESOURCE = "contract/tool-manifest.txt"
        const val GOLDEN_SOURCE_PATH = "src/test/resources/contract/tool-manifest.txt"

        /**
         * Every tool name the plugin advertises. Held separately from the registry so that a tool
         * disappearing from BOTH the registry and the golden file in one commit still fails —
         * otherwise the snapshot would be self-consistent and green.
         */
        val EXPECTED_TOOL_NAMES = setOf(
            "evaluate_expression",
            "execute_run_configuration",
            "get_debug_session_status",
            "get_source_context",
            "get_stack_trace",
            "get_variables",
            "list_breakpoints",
            "list_debug_sessions",
            "list_run_configurations",
            "list_threads",
            "pause_execution",
            "remove_breakpoint",
            "resume_execution",
            "run_to_line",
            "select_stack_frame",
            "set_breakpoint",
            "set_variable",
            "start_debug_session",
            "step_into",
            "step_out",
            "step_over",
            "stop_debug_session",
            "wait_for_pause",
        )
    }

    private val json = Json { encodeDefaults = true }

    private fun registry(): ToolRegistry = ToolRegistry().apply { registerBuiltInTools() }

    @Test
    fun `tool manifest matches golden snapshot`() {
        GoldenFile.assertMatches(GOLDEN_SOURCE_PATH, GOLDEN_RESOURCE, renderManifest())
    }

    @Test
    fun `every advertised tool name is registered and no others`() {
        val registered = registry().getAllTools().map { it.name }.toSet()

        assertEquals(
            "Tools advertised in EXPECTED_TOOL_NAMES but not registered by registerBuiltInTools()",
            emptyList<String>(),
            (EXPECTED_TOOL_NAMES - registered).sorted()
        )
        assertEquals(
            "Tools registered but absent from EXPECTED_TOOL_NAMES — add them here and to the docs",
            emptyList<String>(),
            (registered - EXPECTED_TOOL_NAMES).sorted()
        )
    }

    /**
     * [ToolRegistry.register] keys by name (ToolRegistry.kt:33), so two tools sharing a name
     * silently overwrite each other and the count drops with no other symptom.
     */
    @Test
    fun `registry contains one entry per distinct tool name`() {
        val tools = registry().getAllTools()
        assertEquals(
            "Duplicate tool names would silently overwrite in the registry map",
            tools.map { it.name }.distinct().size,
            tools.size
        )
        assertEquals(EXPECTED_TOOL_NAMES.size, registry().getToolCount())
    }

    /**
     * `AbstractMcpTool` defaults `annotations` to `ToolAnnotations.readOnly("Tool")`
     * (AbstractMcpTool.kt:63). A tool that forgets to override therefore advertises
     * `readOnlyHint = true` — a safety-relevant claim — under the literal title "Tool".
     */
    @Test
    fun `every tool overrides the default annotations`() {
        val offenders = registry().getAllTools()
            .filter { it.annotations?.title == null || it.annotations?.title == "Tool" }
            .map { it.name }
            .sorted()

        assertEquals(
            "These tools inherit AbstractMcpTool's placeholder annotations, so they claim " +
                "readOnlyHint=true with the title \"Tool\". Override `annotations` on each.",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `every tool declares a non-blank description and an object input schema`() {
        registry().getAllTools().forEach { tool ->
            assertTrue("${tool.name} must have a description", tool.description.isNotBlank())
            assertEquals(
                "${tool.name} inputSchema must be an object schema",
                "object",
                tool.inputSchema["type"].toString().trim('"')
            )
        }
    }

    /**
     * Renders the manifest as stable, human-diffable text.
     *
     * Deliberately line-oriented rather than one JSON blob: a review diff then shows exactly
     * which tool and which schema line changed.
     */
    private fun renderManifest(): String {
        val tools = registry().getAllTools().sortedBy { it.name }
        return buildString {
            appendLine("# MCP tool manifest — golden snapshot")
            appendLine("# Regenerate: ./gradlew test --tests \"*ToolManifestContractTest\" -Dcontract.update=true")
            appendLine("# tools: ${tools.size}")
            tools.forEach { tool ->
                appendLine()
                appendLine("## ${tool.name}")
                appendLine("description:")
                tool.description.trim().lines().forEach { appendLine("  $it") }
                appendLine("annotations:")
                appendLine(GoldenFile.canonicalJson(json.encodeToJsonElement(tool.annotations), indent = "  "))
                appendLine("inputSchema:")
                appendLine(GoldenFile.canonicalJson(tool.inputSchema, indent = "  "))
                appendLine("outputSchema:")
                appendLine(
                    tool.outputSchema
                        ?.let { GoldenFile.canonicalJson(it, indent = "  ") }
                        ?: "  <none>"
                )
            }
        }
    }
}
