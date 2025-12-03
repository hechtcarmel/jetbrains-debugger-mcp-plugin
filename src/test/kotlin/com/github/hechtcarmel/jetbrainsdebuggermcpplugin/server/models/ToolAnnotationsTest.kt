package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolAnnotationsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Constructor Tests

    @Test
    fun `ToolAnnotations default values are all null`() {
        val annotations = ToolAnnotations()

        assertNull(annotations.title)
        assertNull(annotations.readOnlyHint)
        assertNull(annotations.destructiveHint)
        assertNull(annotations.idempotentHint)
        assertNull(annotations.openWorldHint)
    }

    @Test
    fun `ToolAnnotations allows setting all values`() {
        val annotations = ToolAnnotations(
            title = "Test Tool",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

        assertEquals("Test Tool", annotations.title)
        assertEquals(true, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    // Factory Method: readOnly

    @Test
    fun `readOnly factory creates correct annotations`() {
        val annotations = ToolAnnotations.readOnly("List Tool")

        assertEquals("List Tool", annotations.title)
        assertEquals(true, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    @Test
    fun `readOnly is suitable for list and get operations`() {
        val listAnnotations = ToolAnnotations.readOnly("List Breakpoints")
        val getAnnotations = ToolAnnotations.readOnly("Get Variables")

        assertTrue(listAnnotations.readOnlyHint == true)
        assertTrue(listAnnotations.idempotentHint == true)
        assertFalse(listAnnotations.destructiveHint == true)

        assertTrue(getAnnotations.readOnlyHint == true)
        assertTrue(getAnnotations.idempotentHint == true)
        assertFalse(getAnnotations.destructiveHint == true)
    }

    // Factory Method: mutable

    @Test
    fun `mutable factory creates non-idempotent state-changing annotations`() {
        val annotations = ToolAnnotations.mutable("Set Breakpoint")

        assertEquals("Set Breakpoint", annotations.title)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(false, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    @Test
    fun `mutable factory with destructive flag sets destructiveHint`() {
        val annotations = ToolAnnotations.mutable("Stop Session", destructive = true)

        assertEquals("Stop Session", annotations.title)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(true, annotations.destructiveHint)
        assertEquals(false, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    @Test
    fun `mutable is suitable for add and modify operations`() {
        val addAnnotations = ToolAnnotations.mutable("Add Breakpoint")
        val modifyAnnotations = ToolAnnotations.mutable("Set Variable")

        assertFalse(addAnnotations.readOnlyHint == true)
        assertFalse(addAnnotations.idempotentHint == true)

        assertFalse(modifyAnnotations.readOnlyHint == true)
        assertFalse(modifyAnnotations.idempotentHint == true)
    }

    // Factory Method: idempotentMutable

    @Test
    fun `idempotentMutable factory creates idempotent state-changing annotations`() {
        val annotations = ToolAnnotations.idempotentMutable("Resume")

        assertEquals("Resume", annotations.title)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    @Test
    fun `idempotentMutable factory with destructive flag sets destructiveHint`() {
        val annotations = ToolAnnotations.idempotentMutable("Clear Breakpoints", destructive = true)

        assertEquals("Clear Breakpoints", annotations.title)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(true, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(false, annotations.openWorldHint)
    }

    @Test
    fun `idempotentMutable is suitable for operations that can be repeated`() {
        val resumeAnnotations = ToolAnnotations.idempotentMutable("Resume Execution")
        val pauseAnnotations = ToolAnnotations.idempotentMutable("Pause Execution")

        assertTrue(resumeAnnotations.idempotentHint == true)
        assertFalse(resumeAnnotations.readOnlyHint == true)

        assertTrue(pauseAnnotations.idempotentHint == true)
        assertFalse(pauseAnnotations.readOnlyHint == true)
    }

    // Serialization Tests

    @Test
    fun `ToolAnnotations serializes correctly`() {
        val annotations = ToolAnnotations.readOnly("Test")
        val encoded = json.encodeToString(annotations)

        assertTrue(encoded.contains("\"title\":\"Test\""))
        assertTrue(encoded.contains("\"readOnlyHint\":true"))
        assertTrue(encoded.contains("\"destructiveHint\":false"))
        assertTrue(encoded.contains("\"idempotentHint\":true"))
    }

    @Test
    fun `ToolAnnotations with nulls serializes correctly`() {
        val annotations = ToolAnnotations(title = "Partial")
        val encoded = json.encodeToString(annotations)

        assertTrue(encoded.contains("\"title\":\"Partial\""))
    }

    @Test
    fun `ToolAnnotations deserialization works`() {
        val jsonStr = """{"title":"Deserialized","readOnlyHint":true,"idempotentHint":false}"""
        val annotations = json.decodeFromString<ToolAnnotations>(jsonStr)

        assertEquals("Deserialized", annotations.title)
        assertEquals(true, annotations.readOnlyHint)
        assertEquals(false, annotations.idempotentHint)
        assertNull(annotations.destructiveHint)
        assertNull(annotations.openWorldHint)
    }

    // Use Case Tests

    @Test
    fun `appropriate annotations for different tool types`() {
        // Read-only inspection tools
        val listBreakpoints = ToolAnnotations.readOnly("List Breakpoints")
        assertTrue(listBreakpoints.readOnlyHint == true)
        assertTrue(listBreakpoints.idempotentHint == true)

        // State-changing non-idempotent tools (adding creates new resource each time)
        val setBreakpoint = ToolAnnotations.mutable("Set Breakpoint")
        assertFalse(setBreakpoint.readOnlyHint == true)
        assertFalse(setBreakpoint.idempotentHint == true)

        // State-changing idempotent tools (same action yields same result)
        val resume = ToolAnnotations.idempotentMutable("Resume")
        assertFalse(resume.readOnlyHint == true)
        assertTrue(resume.idempotentHint == true)

        // Destructive tools
        val removeBreakpoint = ToolAnnotations.mutable("Remove Breakpoint", destructive = true)
        assertTrue(removeBreakpoint.destructiveHint == true)
    }
}
