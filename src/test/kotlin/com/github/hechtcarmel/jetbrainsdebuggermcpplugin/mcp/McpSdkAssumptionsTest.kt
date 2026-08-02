package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the MCP Kotlin SDK behaviours this plugin's wire contract depends on.
 *
 * Every assertion here corresponds to something a client can observe, and every one of them is a
 * property of the SDK rather than of our code — so an SDK version bump, not a refactor, is what
 * breaks this file. That is exactly its job: it converts "the SDK still behaves the way the
 * migration assumed" from a hope into a build failure.
 *
 * Read this alongside `ResultShapeContractTest`, which pins *our* models. This one pins *theirs*.
 */
class McpSdkAssumptionsTest {

    /**
     * `Server.<init>` reaches `kotlin.time.Clock`, which the IntelliJ Platform only ships from
     * 2025.2 (`lib/util-8.jar`). On 2025.1 this throws `NoClassDefFoundError`, which is the entire
     * reason `pluginSinceBuild` is 252.
     *
     * If this fails after a platform downgrade, the plugin is broken at runtime for every user —
     * not just in tests — because nothing else constructs a `Server` until the server starts.
     */
    @Test
    fun `Server can be constructed on the target platform`() {
        val server = Server(
            serverInfo = Implementation(name = "assumption-probe", version = "0.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            ),
            instructions = "probe",
        )

        val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult =
            { _, _ -> CallToolResult(content = listOf(TextContent("ok"))) }
        server.addTool(
            Tool(
                name = "probe_tool",
                description = "probe",
                inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            ),
            handler,
        )

        assertEquals(setOf("probe_tool"), server.tools.keys)
    }

    /**
     * `CallToolResult.isError` is `Boolean?`, and the SDK's `McpJson` sets `explicitNulls = false`.
     * A result built without an explicit `isError` would therefore *omit* the key entirely.
     *
     * The plugin's wire contract (CLAUDE.md, "Error Handling") is that `isError` is always present,
     * including when false — clients branch on it. This is a silent regression with no golden-file
     * signal, because `result-shapes.txt` records the field as nullable-and-optional either way.
     * Hence a literal string assertion.
     */
    @Test
    fun `isError is emitted even when false`() {
        val json = McpJson.encodeToString(
            CallToolResult.serializer(),
            CallToolResult(content = listOf(TextContent("ok")), isError = false),
        )
        assertTrue("isError must survive serialization when false: $json", json.contains("\"isError\":false"))
    }

    /** A tool that never sets `isError` must not silently lose the key either. */
    @Test
    fun `omitting isError omits the key rather than emitting null`() {
        val json = McpJson.encodeToString(
            CallToolResult.serializer(),
            CallToolResult(content = listOf(TextContent("ok"))),
        )
        assertFalse("a null isError must never reach the wire: $json", json.contains("\"isError\":null"))
    }

    /**
     * `TextContent` carries an explicit `type` property rather than relying on a class
     * discriminator, so `"type":"text"` survives despite `McpJson` using
     * `classDiscriminatorMode = NONE`. Clients match on this string.
     */
    @Test
    fun `text content serializes with its type discriminator`() {
        val json = McpJson.encodeToString(
            CallToolResult.serializer(),
            CallToolResult(content = listOf(TextContent("hello"))),
        )
        assertTrue("content blocks must be tagged: $json", json.contains("\"type\":\"text\""))
        assertTrue(json.contains("\"text\":\"hello\""))
    }

    /** `structuredContent` must ride alongside the text block, not replace it. */
    @Test
    fun `structured content and text content coexist`() {
        val json = McpJson.encodeToString(
            CallToolResult.serializer(),
            CallToolResult(
                content = listOf(TextContent("""{"a":1}""")),
                isError = false,
                structuredContent = buildJsonObject { put("a", 1) },
            ),
        )
        assertTrue(json.contains("\"structuredContent\":{\"a\":1}"))
        assertTrue(json.contains("\"type\":\"text\""))
    }

    /**
     * All five annotation hints must reach the wire with their spec names. The plugin advertises
     * `readOnlyHint` on inspection tools, and clients use it to decide what may run unattended —
     * a dropped or renamed hint is a safety-relevant regression.
     */
    @Test
    fun `all five tool annotation hints survive serialization`() {
        val json = McpJson.encodeToString(
            Tool.serializer(),
            Tool(
                name = "probe",
                description = "probe",
                inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
                annotations = ToolAnnotations(
                    title = "Probe",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false,
                ),
            ),
        )
        listOf("title", "readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")
            .forEach { assertTrue("annotation `$it` missing from: $json", json.contains("\"$it\"")) }
    }

    /**
     * `ToolSchema` is `{properties, required, $defs, type}` — there is no `additionalProperties`
     * slot. This test documents the one accepted feature loss of the SDK migration rather than
     * asserting a capability: if a future SDK *gains* schema passthrough this test fails, which is
     * the signal to restore `additionalProperties: false` on all 23 input schemas.
     *
     * See the migration design doc, breaking change B1.
     */
    @Test
    fun `ToolSchema still cannot express additionalProperties`() {
        // Checked on the serial DESCRIPTOR, not on serialized output: if a future SDK adds an
        // `additionalProperties: Boolean? = null` field, a schema built without it would omit the
        // key under explicitNulls=false and a string check would stay green — the exact scenario
        // this canary exists to catch.
        val descriptor = ToolSchema.serializer().descriptor
        val fieldNames = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertFalse(
            "SDK gained additionalProperties support — restore it on the 23 tool schemas and " +
                "delete breaking change B1 from the design doc. Fields: $fieldNames",
            "additionalProperties" in fieldNames,
        )

        val json = McpJson.encodeToString(
            ToolSchema.serializer(),
            ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        )
        assertTrue("schema must still declare its object type: $json", json.contains("\"type\":\"object\""))
    }

    /** An output schema is a `ToolSchema` too, and must serialize under its own key. */
    @Test
    fun `output schema serializes separately from input schema`() {
        val json = McpJson.encodeToString(
            Tool.serializer(),
            Tool(
                name = "probe",
                description = "probe",
                inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
                outputSchema = ToolSchema(
                    properties = buildJsonObject { put("ok", buildJsonObject { put("type", "boolean") }) },
                    required = listOf("ok"),
                ),
            ),
        )
        assertTrue(json.contains("\"inputSchema\""))
        assertTrue(json.contains("\"outputSchema\""))
        assertTrue(json.contains("\"required\":[\"ok\"]"))
    }
}
