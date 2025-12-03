package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AbstractMcpToolTest {

    // Test implementation for testing AbstractMcpTool
    private class TestTool : AbstractMcpTool() {
        override val name = "test_tool"
        override val description = "A test tool"
        override val inputSchema = buildJsonObject {
            put("type", "object")
        }

        override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
            return createSuccessResult("test")
        }

        // Expose protected methods for testing
        fun testCreateSuccessResult(text: String) = createSuccessResult(text)
        fun testCreateErrorResult(message: String) = createErrorResult(message)
        fun testCreateJsonResultVariableInfo(data: VariableInfo) = createJsonResult(data)
        fun testCreateJsonResultList(data: List<VariableInfo>) = createJsonResult(data)
    }

    private val testTool = TestTool()

    // Companion object property methods

    @Test
    fun `projectPathProperty returns correct property definition`() {
        val (name, schema) = AbstractMcpTool.projectPathProperty()

        assertEquals("project_path", name)
        assertEquals("string", schema["type"]?.jsonPrimitive?.content)
        assertTrue(schema["description"]?.jsonPrimitive?.content?.contains("project root") == true)
    }

    @Test
    fun `sessionIdProperty returns correct property definition`() {
        val (name, schema) = AbstractMcpTool.sessionIdProperty()

        assertEquals("session_id", name)
        assertEquals("string", schema["type"]?.jsonPrimitive?.content)
        assertTrue(schema["description"]?.jsonPrimitive?.content?.contains("session") == true)
    }

    @Test
    fun `integerProperty creates basic integer property`() {
        val schema = AbstractMcpTool.integerProperty(
            description = "Line number"
        )

        assertEquals("integer", schema["type"]?.jsonPrimitive?.content)
        assertEquals("Line number", schema["description"]?.jsonPrimitive?.content)
        assertNull(schema["default"])
        assertNull(schema["minimum"])
        assertNull(schema["maximum"])
    }

    @Test
    fun `integerProperty with all options`() {
        val schema = AbstractMcpTool.integerProperty(
            description = "Frame index",
            default = 0,
            minimum = 0,
            maximum = 100
        )

        assertEquals("integer", schema["type"]?.jsonPrimitive?.content)
        assertEquals("Frame index", schema["description"]?.jsonPrimitive?.content)
        assertEquals(0, schema["default"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, schema["minimum"]?.jsonPrimitive?.content?.toInt())
        assertEquals(100, schema["maximum"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `booleanProperty creates basic boolean property`() {
        val schema = AbstractMcpTool.booleanProperty(
            description = "Is enabled"
        )

        assertEquals("boolean", schema["type"]?.jsonPrimitive?.content)
        assertEquals("Is enabled", schema["description"]?.jsonPrimitive?.content)
        assertNull(schema["default"])
    }

    @Test
    fun `booleanProperty with default value`() {
        val schema = AbstractMcpTool.booleanProperty(
            description = "Include variables",
            default = true
        )

        assertEquals("boolean", schema["type"]?.jsonPrimitive?.content)
        assertEquals("Include variables", schema["description"]?.jsonPrimitive?.content)
        assertEquals(true, schema["default"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `stringProperty creates basic string property`() {
        val schema = AbstractMcpTool.stringProperty(
            description = "File path"
        )

        assertEquals("string", schema["type"]?.jsonPrimitive?.content)
        assertEquals("File path", schema["description"]?.jsonPrimitive?.content)
        assertNull(schema["default"])
    }

    @Test
    fun `stringProperty with default value`() {
        val schema = AbstractMcpTool.stringProperty(
            description = "Suspend policy",
            default = "all"
        )

        assertEquals("string", schema["type"]?.jsonPrimitive?.content)
        assertEquals("Suspend policy", schema["description"]?.jsonPrimitive?.content)
        assertEquals("all", schema["default"]?.jsonPrimitive?.content)
    }

    // Result creation methods

    @Test
    fun `createSuccessResult creates non-error result with text content`() {
        val result = testTool.testCreateSuccessResult("Operation completed")

        assertFalse(result.isError)
        assertEquals(1, result.content.size)

        val content = result.content[0] as ContentBlock.Text
        assertEquals("Operation completed", content.text)
    }

    @Test
    fun `createErrorResult creates error result with text content`() {
        val result = testTool.testCreateErrorResult("Something went wrong")

        assertTrue(result.isError)
        assertEquals(1, result.content.size)

        val content = result.content[0] as ContentBlock.Text
        assertEquals("Something went wrong", content.text)
    }

    @Test
    fun `createJsonResult serializes data correctly`() {
        val data = VariableInfo(
            name = "testVar",
            value = "42",
            type = "Int",
            hasChildren = false
        )
        val result = testTool.testCreateJsonResultVariableInfo(data)

        assertFalse(result.isError)
        assertEquals(1, result.content.size)

        val content = result.content[0] as ContentBlock.Text
        assertTrue(content.text.contains("\"name\":\"testVar\""))
        assertTrue(content.text.contains("\"value\":\"42\""))
        assertTrue(content.text.contains("\"type\":\"Int\""))

        assertNotNull(result.structuredContent)
    }

    @Test
    fun `createJsonResult handles list data`() {
        val data = listOf(
            VariableInfo(name = "var1", value = "1", type = "Int", hasChildren = false),
            VariableInfo(name = "var2", value = "2", type = "Int", hasChildren = false)
        )
        val result = testTool.testCreateJsonResultList(data)

        assertFalse(result.isError)
        val content = result.content[0] as ContentBlock.Text
        assertTrue(content.text.startsWith("["))
        assertTrue(content.text.contains("\"var1\""))
        assertTrue(content.text.contains("\"var2\""))
        assertNull(result.structuredContent)
    }

    @Test
    fun `createJsonResult populates structuredContent for object data`() {
        val data = VariableInfo(
            name = "testVar",
            value = "42",
            type = "Int",
            hasChildren = false
        )
        val result = testTool.testCreateJsonResultVariableInfo(data)

        assertFalse(result.isError)
        assertNotNull(result.structuredContent)
        assertEquals("testVar", result.structuredContent?.get("name")?.jsonPrimitive?.content)
        assertEquals("42", result.structuredContent?.get("value")?.jsonPrimitive?.content)
        assertEquals("Int", result.structuredContent?.get("type")?.jsonPrimitive?.content)
    }

    // Default annotations

    @Test
    fun `default annotations are read-only`() {
        val annotations = testTool.annotations

        assertEquals(true, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    // Tool interface implementation

    @Test
    fun `tool has required name and description`() {
        assertEquals("test_tool", testTool.name)
        assertEquals("A test tool", testTool.description)
    }

    @Test
    fun `tool has valid input schema`() {
        val schema = testTool.inputSchema

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }
}
