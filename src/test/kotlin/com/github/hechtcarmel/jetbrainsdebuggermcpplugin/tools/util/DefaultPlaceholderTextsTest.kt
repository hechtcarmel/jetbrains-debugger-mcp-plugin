package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerBundle

/**
 * Pins that the placeholder texts resolved through the public [XDebuggerBundle] are the exact
 * strings the internal `XDebuggerUIConstants.getCollectingDataMessage()` /
 * `getEvaluatingExpressionMessage()` used to supply (issue #51 depends on matching them).
 *
 * If a platform update renames either bundle key, the message() assertions below fail — that is
 * the signal to re-check what transient text the JVM debugger's ClassRenderer now emits.
 */
class DefaultPlaceholderTextsTest : BasePlatformTestCase() {

    fun `test bundle keys resolve to the JVM debugger's transient texts`() {
        assertEquals("Collecting data…", XDebuggerBundle.message("xdebugger.building.tree.node.message"))
        assertEquals("Evaluating…", XDebuggerBundle.message("xdebugger.evaluating.expression.node.message"))
    }

    fun `test default placeholder texts cover both transient messages`() {
        val texts = VariablePresentationUtils.defaultPlaceholderTexts()
        assertTrue(texts.contains("Collecting data…"))
        assertTrue(texts.contains("Evaluating…"))
    }
}
