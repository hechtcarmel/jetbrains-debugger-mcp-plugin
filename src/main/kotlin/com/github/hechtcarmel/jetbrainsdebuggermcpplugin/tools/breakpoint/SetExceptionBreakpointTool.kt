package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetBreakpointResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SetExceptionBreakpointTool : AbstractMcpTool() {

    override val name = "set_exception_breakpoint"

    override val description = """
        Sets a breakpoint that triggers when a specific exception is thrown.
        Can be configured to break on caught, uncaught, or both types of exceptions.
        Works with Java and Kotlin exception classes.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("exception_class") {
                put("type", "string")
                put("description", "Fully qualified exception class name (e.g., java.lang.NullPointerException)")
            }
            putJsonObject("caught") {
                put("type", "boolean")
                put("description", "Break when exception is caught. Default: true")
            }
            putJsonObject("uncaught") {
                put("type", "boolean")
                put("description", "Break when exception is uncaught. Default: true")
            }
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "Whether breakpoint is enabled. Default: true")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("exception_class"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val exceptionClass = arguments["exception_class"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: exception_class")
        val caught = arguments["caught"]?.jsonPrimitive?.booleanOrNull ?: true
        val uncaught = arguments["uncaught"]?.jsonPrimitive?.booleanOrNull ?: true
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

        val breakpointManager = getDebuggerManager(project).breakpointManager

        val exceptionBreakpointType = XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .find { it.id == "java-exception" }

        if (exceptionBreakpointType == null) {
            return createErrorResult("Java exception breakpoints are not available (Java debugger not loaded)")
        }

        return try {
            val breakpointId = withContext(Dispatchers.Main) {
                ApplicationManager.getApplication().runWriteAction<String?> {
                    try {
                        val breakpoint = createExceptionBreakpoint(
                            breakpointManager,
                            exceptionBreakpointType,
                            exceptionClass,
                            caught,
                            uncaught,
                            enabled
                        )
                        breakpoint?.hashCode()?.toString()
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            if (breakpointId == null) {
                return createErrorResult("Failed to create exception breakpoint for $exceptionClass")
            }

            createJsonResult(SetBreakpointResult(
                breakpointId = breakpointId,
                status = "set",
                verified = true,
                message = "Exception breakpoint set for $exceptionClass (caught=$caught, uncaught=$uncaught)"
            ))
        } catch (e: Exception) {
            createErrorResult("Failed to set exception breakpoint: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createExceptionBreakpoint(
        breakpointManager: com.intellij.xdebugger.breakpoints.XBreakpointManager,
        breakpointType: XBreakpointType<*, *>,
        exceptionClass: String,
        caught: Boolean,
        uncaught: Boolean,
        enabled: Boolean
    ): XBreakpoint<*>? {
        try {
            val createPropertiesMethod = breakpointType.javaClass.getMethod("createProperties")
            val properties: Any = createPropertiesMethod.invoke(breakpointType) ?: return null

            setExceptionProperties(properties, exceptionClass, caught, uncaught)

            val addBreakpointMethod = breakpointManager.javaClass.methods.find { method ->
                method.name == "addBreakpoint" && method.parameterCount == 2
            } ?: return null

            val result: Any? = addBreakpointMethod.invoke(breakpointManager, breakpointType, properties)
            val breakpoint = result as? XBreakpoint<*>
            breakpoint?.isEnabled = enabled
            return breakpoint
        } catch (e: Exception) {
            return null
        }
    }

    private fun setExceptionProperties(properties: Any, exceptionClass: String, caught: Boolean, uncaught: Boolean) {
        val propsClass = properties.javaClass
        try {
            propsClass.methods.find { it.name == "setException" }?.invoke(properties, exceptionClass)
            propsClass.methods.find { it.name == "setNotifyCaught" }?.invoke(properties, caught)
            propsClass.methods.find { it.name == "setNotifyUncaught" }?.invoke(properties, uncaught)
        } catch (e: Exception) {
            try {
                val myQualifiedNameField = propsClass.getDeclaredField("myQualifiedName")
                myQualifiedNameField.isAccessible = true
                myQualifiedNameField.set(properties, exceptionClass)
            } catch (e2: Exception) {
                // Properties may have different structure in different IDE versions
            }
        }
    }
}
