package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExpandVariableResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.Icon
import kotlin.coroutines.resume

class ExpandVariableTool : AbstractMcpTool() {

    override val name = "expand_variable"

    override val description = """
        Expands a variable to show its children (fields, elements, etc.).
        Use the variable_path from get_variables or a previous expand_variable call.
        Only works on variables where hasChildren is true.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            putJsonObject("variable_path") {
                put("type", "string")
                put("description", "Dot-separated path to variable (e.g., 'person', 'person.hobbies', 'myObject.field.subField')")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("variable_path"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val variablePath = arguments["variable_path"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: variable_path")

        val session = resolveSession(project, sessionId)
            ?: return createErrorResult(
                if (sessionId != null) "Session not found: $sessionId"
                else "No active debug session"
            )

        if (!session.isPaused) {
            return createErrorResult("Session must be paused to expand variables")
        }

        val currentFrame = session.currentStackFrame
            ?: return createErrorResult("No current stack frame")

        val (variableName, variable) = findVariableByPath(currentFrame, variablePath)
            ?: return createErrorResult("Variable not found at path: $variablePath")

        val children = expandVariable(variable)

        return createJsonResult(ExpandVariableResult(
            sessionId = getSessionId(session),
            variablePath = variablePath,
            name = variableName,
            children = children
        ))
    }

    private suspend fun findVariableByName(frame: XStackFrame, targetName: String): Pair<String, XValue>? {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                var found: Pair<String, XValue>? = null

                frame.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            val name = children.getName(i)
                            val value = children.getValue(i)
                            if (name == targetName) {
                                found = Pair(name, value)
                            }
                        }
                        if (last) {
                            continuation.resume(found)
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) {
                        continuation.resume(null)
                    }
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        continuation.resume(null)
                    }
                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                    override fun tooManyChildren(remaining: Int) {}
                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}
                    override fun isObsolete(): Boolean = false
                })
            }
        }
    }

    private suspend fun findVariableByPath(frame: XStackFrame, path: String): Pair<String, XValue>? {
        val parts = path.split(".")
        if (parts.isEmpty()) return null

        // Find the root variable
        var current: Pair<String, XValue> = findVariableByName(frame, parts[0]) ?: return null

        // Navigate through the path
        for (i in 1 until parts.size) {
            val fieldName = parts[i]
            current = findChildByName(current.second, fieldName) ?: return null
        }

        return current
    }

    private suspend fun findChildByName(parent: XValue, childName: String): Pair<String, XValue>? {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                var found: Pair<String, XValue>? = null

                parent.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            val name = children.getName(i)
                            val value = children.getValue(i)
                            if (name == childName) {
                                found = Pair(name, value)
                            }
                        }
                        if (last) {
                            continuation.resume(found)
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) {
                        continuation.resume(null)
                    }
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        continuation.resume(null)
                    }
                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                    override fun tooManyChildren(remaining: Int) {}
                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}
                    override fun isObsolete(): Boolean = false
                })
            }
        }
    }

    private suspend fun expandVariable(variable: XValue): List<VariableInfo> {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                val children = mutableListOf<VariableInfo>()
                var pendingPresentations = 0
                var completed = false

                variable.computeChildren(object : XCompositeNode {
                    override fun addChildren(childList: XValueChildrenList, last: Boolean) {
                        pendingPresentations += childList.size()

                        for (i in 0 until childList.size()) {
                            val name = childList.getName(i)
                            val value = childList.getValue(i)

                            computeValuePresentation(name, value) { varInfo ->
                                synchronized(children) {
                                    children.add(varInfo)
                                    pendingPresentations--

                                    if (last && pendingPresentations <= 0 && !completed) {
                                        completed = true
                                        continuation.resume(children.toList())
                                    }
                                }
                            }
                        }

                        if (last && pendingPresentations <= 0 && !completed) {
                            completed = true
                            continuation.resume(children.toList())
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) {
                        if (!completed) {
                            completed = true
                            continuation.resume(emptyList())
                        }
                    }
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        if (!completed) {
                            completed = true
                            continuation.resume(emptyList())
                        }
                    }
                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                    override fun tooManyChildren(remaining: Int) {}
                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}
                    override fun isObsolete(): Boolean = false
                })
            }
        } ?: emptyList()
    }

    private fun computeValuePresentation(
        name: String,
        value: XValue,
        callback: (VariableInfo) -> Unit
    ) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(VariableInfo(
                    name = name,
                    value = valueText,
                    type = type ?: "unknown",
                    hasChildren = hasChildren
                ))
            }

            override fun setPresentation(
                icon: Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean
            ) {
                val valueText = buildString {
                    presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(v: String) { append(v) }
                        override fun renderStringValue(v: String) { append("\"$v\"") }
                        override fun renderNumericValue(v: String) { append(v) }
                        override fun renderKeywordValue(v: String) { append(v) }
                        override fun renderValue(
                            v: String,
                            key: com.intellij.openapi.editor.colors.TextAttributesKey
                        ) { append(v) }
                        override fun renderStringValue(
                            v: String,
                            additionalSpecialCharsToHighlight: String?,
                            maxLength: Int
                        ) { append("\"$v\"") }
                        override fun renderComment(comment: String) { append(" // $comment") }
                        override fun renderSpecialSymbol(symbol: String) { append(symbol) }
                        override fun renderError(error: String) { append("ERROR: $error") }
                    })
                }

                callback(VariableInfo(
                    name = name,
                    value = valueText,
                    type = presentation.type ?: "unknown",
                    hasChildren = hasChildren
                ))
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
    }
}
