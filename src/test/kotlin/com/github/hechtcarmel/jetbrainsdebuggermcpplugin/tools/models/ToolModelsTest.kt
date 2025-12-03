package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // Session Models Tests

    @Test
    fun `DebugSessionInfo serialization`() {
        val info = DebugSessionInfo(
            id = "sess-123",
            name = "Test Session",
            state = "running",
            isCurrent = true,
            runConfigurationName = "Main"
        )

        val encoded = json.encodeToString(info)

        assertTrue(encoded.contains("\"id\":\"sess-123\""))
        assertTrue(encoded.contains("\"name\":\"Test Session\""))
        assertTrue(encoded.contains("\"isCurrent\":true"))
    }

    @Test
    fun `SourceLocation serialization`() {
        val location = SourceLocation(
            file = "/path/to/file.kt",
            line = 42,
            className = "MyClass",
            methodName = "myMethod"
        )

        val encoded = json.encodeToString(location)

        assertTrue(encoded.contains("\"file\":\"/path/to/file.kt\""))
        assertTrue(encoded.contains("\"line\":42"))
    }

    // Breakpoint Models Tests

    @Test
    fun `BreakpointInfo serialization`() {
        val info = BreakpointInfo(
            id = "bp-1",
            type = "line",
            file = "/path/to/file.kt",
            line = 10,
            enabled = true,
            condition = "x > 5"
        )

        val encoded = json.encodeToString(info)

        assertTrue(encoded.contains("\"id\":\"bp-1\""))
        assertTrue(encoded.contains("\"type\":\"line\""))
        assertTrue(encoded.contains("\"condition\":\"x > 5\""))
    }

    @Test
    fun `SetBreakpointResult serialization`() {
        val result = SetBreakpointResult(
            breakpointId = "bp-new",
            status = "set",
            verified = true,
            message = "Breakpoint set successfully",
            file = "/path/file.kt",
            line = 25
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"breakpointId\":\"bp-new\""))
        assertTrue(encoded.contains("\"verified\":true"))
    }

    @Test
    fun `RemoveBreakpointResult serialization`() {
        val result = RemoveBreakpointResult(
            breakpointId = "bp-removed",
            status = "removed",
            message = "Breakpoint removed"
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"status\":\"removed\""))
    }

    // Stack Models Tests

    @Test
    fun `StackFrameInfo serialization`() {
        val frame = StackFrameInfo(
            index = 0,
            file = "/path/file.kt",
            line = 100,
            className = "com.example.Test",
            methodName = "runTest",
            isCurrent = true,
            isLibrary = false
        )

        val encoded = json.encodeToString(frame)

        assertTrue(encoded.contains("\"index\":0"))
        assertTrue(encoded.contains("\"isCurrent\":true"))
        assertTrue(encoded.contains("\"className\":\"com.example.Test\""))
    }

    @Test
    fun `ThreadInfo serialization`() {
        val thread = ThreadInfo(
            id = "thread-1",
            name = "main",
            state = "RUNNING",
            isCurrent = true
        )

        val encoded = json.encodeToString(thread)

        assertTrue(encoded.contains("\"name\":\"main\""))
        assertTrue(encoded.contains("\"state\":\"RUNNING\""))
    }

    // Variable Models Tests

    @Test
    fun `VariableInfo serialization`() {
        val variable = VariableInfo(
            name = "myVar",
            value = "42",
            type = "Int",
            hasChildren = false
        )

        val encoded = json.encodeToString(variable)

        assertTrue(encoded.contains("\"name\":\"myVar\""))
        assertTrue(encoded.contains("\"value\":\"42\""))
        assertTrue(encoded.contains("\"type\":\"Int\""))
    }

    @Test
    fun `VariableInfo with children serialization`() {
        val variable = VariableInfo(
            name = "myObject",
            value = "Object@123",
            type = "MyClass",
            hasChildren = true
        )

        val encoded = json.encodeToString(variable)

        assertTrue(encoded.contains("\"hasChildren\":true"))
    }

    @Test
    fun `SetVariableResult serialization`() {
        val result = SetVariableResult(
            sessionId = "sess-1",
            variableName = "x",
            oldValue = "5",
            newValue = "10",
            type = "Int",
            message = "Variable updated"
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"oldValue\":\"5\""))
        assertTrue(encoded.contains("\"newValue\":\"10\""))
    }

    // Evaluation Models Tests

    @Test
    fun `EvaluationResult serialization`() {
        val result = EvaluationResult(
            expression = "1 + 1",
            value = "2",
            type = "Int",
            hasChildren = false
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"value\":\"2\""))
        assertTrue(encoded.contains("\"type\":\"Int\""))
    }

    @Test
    fun `EvaluationResult with error serialization`() {
        val result = EvaluationResult(
            expression = "undefined",
            value = "",
            type = "",
            hasChildren = false,
            error = "Evaluation failed"
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"error\":\"Evaluation failed\""))
    }

    // Run Configuration Models Tests

    @Test
    fun `RunConfigurationInfo serialization`() {
        val config = RunConfigurationInfo(
            name = "Main",
            type = "Application",
            typeId = "Application",
            canDebug = true
        )

        val encoded = json.encodeToString(config)

        assertTrue(encoded.contains("\"name\":\"Main\""))
        assertTrue(encoded.contains("\"canDebug\":true"))
    }

    @Test
    fun `ExecutionControlResult serialization`() {
        val result = ExecutionControlResult(
            sessionId = "sess-1",
            action = "resume",
            status = "success",
            message = "Execution resumed"
        )

        val encoded = json.encodeToString(result)

        assertTrue(encoded.contains("\"action\":\"resume\""))
        assertTrue(encoded.contains("\"status\":\"success\""))
    }
}
