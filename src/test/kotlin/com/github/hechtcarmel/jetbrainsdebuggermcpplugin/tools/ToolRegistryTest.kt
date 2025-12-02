package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

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
        assertNotNull("resume should be registered", registry.getTool("resume"))
        assertNotNull("step_over should be registered", registry.getTool("step_over"))
        assertNotNull("get_variables should be registered", registry.getTool("get_variables"))
        assertNotNull("evaluate should be registered", registry.getTool("evaluate"))
    }

    @Test
    fun `tool definitions have required schema properties`() {
        registry.register(createMockTool("schema_test", "Test schema"))

        val definitions = registry.getToolDefinitions()
        val schema = definitions[0].inputSchema

        assertEquals("object", schema["type"]?.toString()?.trim('"'))
        assertTrue(schema.containsKey("properties"))
    }

    private fun createMockTool(name: String, description: String): McpTool {
        return object : McpTool {
            override val name: String = name
            override val description: String = description
            override val inputSchema: JsonObject = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }

            override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
                return ToolCallResult(content = emptyList(), isError = false)
            }
        }
    }
}
