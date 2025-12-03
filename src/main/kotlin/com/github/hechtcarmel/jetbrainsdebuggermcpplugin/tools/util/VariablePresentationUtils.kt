package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import javax.swing.Icon

/**
 * Utility class for extracting presentation information from XValue objects.
 */
object VariablePresentationUtils {

    /**
     * Data class to hold value presentation info (value text and type).
     */
    data class ValuePresentation(
        val value: String,
        val type: String,
        val hasChildren: Boolean
    )

    /**
     * Computes the value presentation for an XValue and returns it via callback.
     * This extracts the display value, type, and hasChildren flag.
     */
    fun computeValuePresentation(
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
                val valueText = renderPresentation(presentation)
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

    /**
     * Computes value presentation returning just the value text and type (no name).
     * Useful for SetVariableTool where we need value and type separately.
     */
    fun computeSimplePresentation(
        value: XValue,
        callback: (valueText: String, type: String) -> Unit
    ) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(valueText, type ?: "unknown")
            }

            override fun setPresentation(
                icon: Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean
            ) {
                val valueText = renderPresentation(presentation)
                callback(valueText, presentation.type ?: "unknown")
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
    }

    /**
     * Computes full value presentation including hasChildren flag.
     */
    fun computeFullPresentation(
        value: XValue,
        callback: (ValuePresentation) -> Unit
    ) {
        value.computePresentation(object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                callback(ValuePresentation(valueText, type ?: "unknown", hasChildren))
            }

            override fun setPresentation(
                icon: Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean
            ) {
                val valueText = renderPresentation(presentation)
                callback(ValuePresentation(valueText, presentation.type ?: "unknown", hasChildren))
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
    }

    /**
     * Renders an XValuePresentation to a string.
     */
    private fun renderPresentation(presentation: XValuePresentation): String {
        return buildString {
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
    }
}
