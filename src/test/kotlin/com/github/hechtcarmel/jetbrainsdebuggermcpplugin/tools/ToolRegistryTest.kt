package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        registry = ToolRegistry()
    }

    @Test
    fun `registry starts empty`() {
        assertEquals(0, registry.getToolCount())
        assertTrue(registry.getAllTools().isEmpty())
    }

    @Test
    fun `register adds tool to registry`() {
        val tool = createMockTool("test_tool", "Test description")

        registry.register(tool)

        assertEquals(1, registry.getToolCount())
        assertNotNull(registry.getTool("test_tool"))
    }

    @Test
    fun `getTool returns null for unknown tool`() {
        assertNull(registry.getTool("unknown_tool"))
    }

    @Test
    fun `getTool returns registered tool`() {
        val tool = createMockTool("my_tool", "My description")
        registry.register(tool)

        val retrieved = registry.getTool("my_tool")

        assertNotNull(retrieved)
        assertEquals("my_tool", retrieved?.name)
        assertEquals("My description", retrieved?.description)
    }

    @Test
    fun `unregister removes tool from registry`() {
        val tool = createMockTool("temp_tool", "Temporary")
        registry.register(tool)
        assertEquals(1, registry.getToolCount())

        registry.unregister("temp_tool")

        assertEquals(0, registry.getToolCount())
        assertNull(registry.getTool("temp_tool"))
    }

    @Test
    fun `unregister does nothing for unknown tool`() {
        val tool = createMockTool("existing", "Existing tool")
        registry.register(tool)

        registry.unregister("nonexistent")

        assertEquals(1, registry.getToolCount())
    }

    @Test
    fun `getAllTools returns all registered tools`() {
        registry.register(createMockTool("tool1", "Description 1"))
        registry.register(createMockTool("tool2", "Description 2"))
        registry.register(createMockTool("tool3", "Description 3"))

        val allTools = registry.getAllTools()

        assertEquals(3, allTools.size)
        assertTrue(allTools.any { it.name == "tool1" })
        assertTrue(allTools.any { it.name == "tool2" })
        assertTrue(allTools.any { it.name == "tool3" })
    }

    @Test
    fun `getToolDefinitions returns correct definitions`() {
        registry.register(createMockTool("def_tool", "Definition description"))

        val definitions = registry.getToolDefinitions()

        assertEquals(1, definitions.size)
        assertEquals("def_tool", definitions[0].name)
        assertEquals("Definition description", definitions[0].description)
        assertNotNull(definitions[0].inputSchema)
    }

    @Test
    fun `register overwrites existing tool with same name`() {
        val tool1 = createMockTool("same_name", "First description")
        val tool2 = createMockTool("same_name", "Second description")

        registry.register(tool1)
        registry.register(tool2)

        assertEquals(1, registry.getToolCount())
        assertEquals("Second description", registry.getTool("same_name")?.description)
    }

    @Test
    fun `registerBuiltInTools registers all expected tools`() {
        registry.registerBuiltInTools()

        assertTrue("Should have at least 20 tools", registry.getToolCount() >= 20)

        // Verify key tools are registered
        assertNotNull("list_run_configurations should be registered", registry.getTool("list_run_configurations"))
        assertNotNull("start_debug_session should be registered", registry.getTool("start_debug_session"))
        assertNotNull("set_breakpoint should be registered", registry.getTool("set_breakpoint"))
        assertNotNull("resume_execution should be registered", registry.getTool("resume_execution"))
        assertNotNull("step_over should be registered", registry.getTool("step_over"))
        assertNotNull("get_variables should be registered", registry.getTool("get_variables"))
        assertNotNull("evaluate_expression should be registered", registry.getTool("evaluate_expression"))
    }

    @Test
    fun `tool definitions have required schema properties`() {
        registry.register(createMockTool("schema_test", "Test schema"))

        val definitions = registry.getToolDefinitions()
        val schema = definitions[0].inputSchema

        assertEquals("object", schema["type"]?.toString()?.trim('"'))
        assertTrue(schema.containsKey("properties"))
    }

    @Test
    fun `getToolDefinitions includes annotations`() {
        val tool = createMockToolWithAnnotations("annotated_tool", "Tool with annotations", ToolAnnotations.readOnly("Test"))
        registry.register(tool)

        val definitions = registry.getToolDefinitions()
        val definition = definitions.find { it.name == "annotated_tool" }

        assertNotNull(definition?.annotations)
        assertEquals("Test", definition?.annotations?.title)
        assertEquals(true, definition?.annotations?.readOnlyHint)
    }

    @Test
    fun `registerBuiltInTools registers exactly 24 tools`() {
        registry.registerBuiltInTools()

        assertEquals(25, registry.getToolCount())
    }

    @Test
    fun `registerBuiltInTools registers all tool categories`() {
        registry.registerBuiltInTools()

        // Run Configuration Tools (3)
        assertNotNull(registry.getTool("list_run_configurations"))
        assertNotNull(registry.getTool("list_run_sessions"))
        assertNotNull(registry.getTool("execute_run_configuration"))

        // Debug Session Tools (4)
        assertNotNull(registry.getTool("list_debug_sessions"))
        assertNotNull(registry.getTool("start_debug_session"))
        assertNotNull(registry.getTool("stop_debug_session"))
        assertNotNull(registry.getTool("get_debug_session_status"))

        // Breakpoint Tools (3)
        assertNotNull(registry.getTool("list_breakpoints"))
        assertNotNull(registry.getTool("set_breakpoint"))
        assertNotNull(registry.getTool("remove_breakpoint"))

        // Execution Control Tools (6)
        assertNotNull(registry.getTool("resume_execution"))
        assertNotNull(registry.getTool("pause_execution"))
        assertNotNull(registry.getTool("step_over"))
        assertNotNull(registry.getTool("step_into"))
        assertNotNull(registry.getTool("step_out"))
        assertNotNull(registry.getTool("run_to_line"))

        // Stack Frame Tools (3)
        assertNotNull(registry.getTool("get_stack_trace"))
        assertNotNull(registry.getTool("select_stack_frame"))
        assertNotNull(registry.getTool("list_threads"))

        // Variable Tools (2)
        assertNotNull(registry.getTool("get_variables"))
        assertNotNull(registry.getTool("set_variable"))

        // Navigation Tools (1)
        assertNotNull(registry.getTool("get_source_context"))

        // Evaluation Tools (1)
        assertNotNull(registry.getTool("evaluate_expression"))
    }

    @Test
    fun `getTool is case sensitive`() {
        registry.register(createMockTool("MyTool", "Description"))

        assertNotNull(registry.getTool("MyTool"))
        assertNull(registry.getTool("mytool"))
        assertNull(registry.getTool("MYTOOL"))
    }

    @Test
    fun `getAllTools returns copy not reference`() {
        registry.register(createMockTool("tool", "Description"))

        val tools1 = registry.getAllTools()
        val tools2 = registry.getAllTools()

        assertNotSame(tools1, tools2)
    }

    @Test
    fun `getToolDefinitions returns copy not reference`() {
        registry.register(createMockTool("tool", "Description"))

        val defs1 = registry.getToolDefinitions()
        val defs2 = registry.getToolDefinitions()

        assertNotSame(defs1, defs2)
    }

    @Test
    fun `register with empty name still works`() {
        val tool = createMockTool("", "Empty name tool")
        registry.register(tool)

        assertEquals(1, registry.getToolCount())
        assertNotNull(registry.getTool(""))
    }

    @Test
    fun `register with special characters in name`() {
        val tool = createMockTool("tool-with_special.chars", "Special")
        registry.register(tool)

        assertNotNull(registry.getTool("tool-with_special.chars"))
    }

    private fun createMockTool(name: String, description: String): McpTool {
        return createMockToolWithAnnotations(name, description, ToolAnnotations.readOnly(name))
    }

    private fun createMockToolWithAnnotations(name: String, description: String, toolAnnotations: ToolAnnotations): McpTool {
        return object : McpTool {
            override val name: String = name
            override val description: String = description
            override val inputSchema: JsonObject = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
            override val annotations: ToolAnnotations = toolAnnotations

            override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
                return ToolCallResult(content = emptyList(), isError = false)
            }
        }
    }
}
