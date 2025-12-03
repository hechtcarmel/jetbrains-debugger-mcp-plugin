package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class JsonUtilsTest {

    @Test
    fun `mcpJson ignores unknown keys`() {
        val jsonString = """{"known":"value","unknown":"ignored"}"""
        val element = mcpJson.parseToJsonElement(jsonString)
        assertNotNull(element)
    }

    @Test
    fun `mcpJson is lenient with input`() {
        val jsonString = """{"key":"value"}"""
        val element = mcpJson.parseToJsonElement(jsonString)
        assertEquals("value", element.jsonObject["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mcpJsonPretty produces formatted output`() {
        val obj = buildJsonObject { put("key", "value") }
        val output = mcpJsonPretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
        assertTrue(output.contains("\n"))
    }

    @Test
    fun `buildInputSchema creates valid object schema`() {
        val schema = buildInputSchema {
            put("name", buildJsonObject {
                put("type", "string")
            })
        }

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertNotNull(schema["properties"])
    }

    @Test
    fun `buildInputSchema with required properties includes required array`() {
        val schema = buildInputSchema(requiredProperties = listOf("file_path", "line")) {
            stringProperty("file_path", "Path to file")
            intProperty("line", "Line number")
        }

        val requiredArray = schema["required"]?.jsonArray
        assertNotNull(requiredArray)
        assertEquals(2, requiredArray?.size)
        assertTrue(requiredArray?.map { it.jsonPrimitive.content }?.contains("file_path") == true)
        assertTrue(requiredArray?.map { it.jsonPrimitive.content }?.contains("line") == true)
    }

    @Test
    fun `buildInputSchema without required properties omits required array`() {
        val schema = buildInputSchema {
            stringProperty("optional", "Optional field")
        }

        assertNull(schema["required"])
    }

    @Test
    fun `stringProperty creates string type property`() {
        val schema = buildInputSchema {
            stringProperty("name", "The name")
        }

        val properties = schema["properties"]?.jsonObject
        val nameProp = properties?.get("name")?.jsonObject

        assertEquals("string", nameProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("The name", nameProp?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `intProperty creates integer type property`() {
        val schema = buildInputSchema {
            intProperty("count", "The count")
        }

        val properties = schema["properties"]?.jsonObject
        val countProp = properties?.get("count")?.jsonObject

        assertEquals("integer", countProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("The count", countProp?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `intProperty with minimum includes minimum constraint`() {
        val schema = buildInputSchema {
            intProperty("line", "Line number", minimum = 1)
        }

        val properties = schema["properties"]?.jsonObject
        val lineProp = properties?.get("line")?.jsonObject

        assertEquals(1, lineProp?.get("minimum")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `booleanProperty creates boolean type property`() {
        val schema = buildInputSchema {
            booleanProperty("enabled", "Whether enabled")
        }

        val properties = schema["properties"]?.jsonObject
        val enabledProp = properties?.get("enabled")?.jsonObject

        assertEquals("boolean", enabledProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("Whether enabled", enabledProp?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `enumProperty creates string type with enum values`() {
        val schema = buildInputSchema {
            enumProperty("status", "The status", listOf("pending", "running", "complete"))
        }

        val properties = schema["properties"]?.jsonObject
        val statusProp = properties?.get("status")?.jsonObject

        assertEquals("string", statusProp?.get("type")?.jsonPrimitive?.content)

        val enumValues = statusProp?.get("enum")?.jsonArray
        assertNotNull(enumValues)
        assertEquals(3, enumValues?.size)
        assertEquals("pending", enumValues?.get(0)?.jsonPrimitive?.content)
        assertEquals("running", enumValues?.get(1)?.jsonPrimitive?.content)
        assertEquals("complete", enumValues?.get(2)?.jsonPrimitive?.content)
    }

    @Test
    fun `projectPathProperty creates standard project path property`() {
        val schema = buildInputSchema {
            projectPathProperty()
        }

        val properties = schema["properties"]?.jsonObject
        val projectPathProp = properties?.get("projectPath")?.jsonObject

        assertEquals("string", projectPathProp?.get("type")?.jsonPrimitive?.content)
        assertTrue(projectPathProp?.get("description")?.jsonPrimitive?.content?.contains("project root") == true)
    }

    @Test
    fun `sessionIdProperty creates standard session id property`() {
        val schema = buildInputSchema {
            sessionIdProperty()
        }

        val properties = schema["properties"]?.jsonObject
        val sessionIdProp = properties?.get("session_id")?.jsonObject

        assertEquals("string", sessionIdProp?.get("type")?.jsonPrimitive?.content)
        assertTrue(sessionIdProp?.get("description")?.jsonPrimitive?.content?.contains("session") == true)
    }

    @Test
    fun `putJsonObject extension creates nested object`() {
        val obj = buildJsonObject {
            putJsonObject("nested") {
                put("inner", "value")
            }
        }

        val nested = obj["nested"]?.jsonObject
        assertEquals("value", nested?.get("inner")?.jsonPrimitive?.content)
    }

    @Test
    fun `putJsonArray extension creates array`() {
        val obj = buildJsonObject {
            putJsonArray("items") {
                add(JsonPrimitive("one"))
                add(JsonPrimitive("two"))
            }
        }

        val items = obj["items"]?.jsonArray
        assertEquals(2, items?.size)
        assertEquals("one", items?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun `complex schema with multiple property types`() {
        val schema = buildInputSchema(requiredProperties = listOf("file_path", "line")) {
            projectPathProperty()
            sessionIdProperty()
            stringProperty("file_path", "Path to the source file")
            intProperty("line", "1-based line number", minimum = 1)
            booleanProperty("enabled", "Whether breakpoint is enabled")
            enumProperty("suspend_policy", "Suspend policy", listOf("all", "thread", "none"))
        }

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertEquals(6, properties?.size)

        val required = schema["required"]?.jsonArray
        assertEquals(2, required?.size)
    }
}
