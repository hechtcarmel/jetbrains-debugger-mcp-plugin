package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.xdebugger.XDebuggerBundle
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Stable, locale-independent marker for values whose async computation did not finish in time. */
    const val PENDING_VALUE_TEXT = "<value not yet computed>"

    /** Stable marker for values that produced no presentation at all. */
    const val UNAVAILABLE_VALUE_TEXT = "<unavailable>"

    private const val DEFAULT_PRESENTATION_TIMEOUT_MS = 3000L

    /**
     * Transient texts the platform debuggers show while computing a value asynchronously.
     * The JVM debugger's ClassRenderer returns the "Collecting data…" message until the
     * debuggee-side toString() evaluation completes, then fires a second setPresentation with
     * the real value (see issue #51).
     *
     * The bundle keys mirror the internal `XDebuggerUIConstants.getCollectingDataMessage()` /
     * `getEvaluatingExpressionMessage()` (an `.impl.` class this plugin must not depend on);
     * [XDebuggerBundle] is the public API both read from. The English literals are a safety
     * net in case a platform update renames the keys.
     */
    fun defaultPlaceholderTexts(): Set<String> = setOf(
        XDebuggerBundle.message("xdebugger.building.tree.node.message"),
        XDebuggerBundle.message("xdebugger.evaluating.expression.node.message"),
        "Collecting data…",
        "Evaluating…"
    )

    /**
     * The most recent presentation together with its placeholder flag, stored as one
     * unit so the timeout fallback reads a consistent pair (no torn read between
     * a presentation update and its placeholder classification).
     */
    data class PresentationSnapshot(
        val presentation: ValuePresentation,
        val isPlaceholder: Boolean
    )

    /**
     * XValueNode that tolerates the XDebugger multi-call presentation protocol:
     * setPresentation may be invoked several times (placeholder first, real value later).
     * Completes [awaitFinal] on the first non-placeholder presentation and always
     * remembers the latest one for timeout fallbacks.
     */
    class AwaitableValueNode(
        placeholderTexts: Set<String>
    ) : XValueNode {

        private val normalizedPlaceholders =
            placeholderTexts.map { normalizeForPlaceholderMatch(it) }.toSet()

        private val finalPresentation = CompletableDeferred<ValuePresentation>()

        @Volatile
        private var latest: PresentationSnapshot? = null

        @Volatile
        private var obsolete = false

        suspend fun awaitFinal(): ValuePresentation = finalPresentation.await()

        fun latestSnapshot(): PresentationSnapshot? = latest

        fun markObsolete() {
            obsolete = true
        }

        override fun isObsolete(): Boolean = obsolete

        override fun setPresentation(
            icon: Icon?,
            type: String?,
            valueText: String,
            hasChildren: Boolean
        ) {
            offer(
                ValuePresentation(valueText, type ?: "unknown", hasChildren),
                isPlaceholderText(valueText)
            )
        }

        override fun setPresentation(
            icon: Icon?,
            presentation: XValuePresentation,
            hasChildren: Boolean
        ) {
            val renderer = PlaceholderAwareTextRenderer(::isPlaceholderText)
            presentation.renderValue(renderer)
            offer(
                ValuePresentation(renderer.renderedText(), presentation.type ?: "unknown", hasChildren),
                renderer.sawPlaceholder
            )
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

        private fun isPlaceholderText(text: String): Boolean =
            normalizeForPlaceholderMatch(text) in normalizedPlaceholders

        private fun offer(presentation: ValuePresentation, isPlaceholder: Boolean) {
            latest = PresentationSnapshot(presentation, isPlaceholder)
            if (!isPlaceholder) {
                finalPresentation.complete(presentation)
            }
        }
    }

    /**
     * Placeholder comparison tolerates cosmetic variations observed in the wild
     * (issue #51 and the SettingDust fork): some paths deliver the placeholder
     * pre-quoted, and the trailing ellipsis appears either as U+2026 or as three
     * dots depending on platform version and renderer.
     */
    private fun normalizeForPlaceholderMatch(text: String): String {
        val trimmed = text.trim()
        val unquoted = if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return unquoted.replace("…", "...")
    }

    /**
     * Renders presentation fragments to text and flags placeholder fragments.
     * Placeholders can arrive wrapped (e.g. via renderStringValue for String results),
     * so detection happens per raw fragment, before quoting.
     */
    private class PlaceholderAwareTextRenderer(
        private val isPlaceholder: (String) -> Boolean
    ) : XValuePresentation.XValueTextRenderer {

        private val builder = StringBuilder()

        var sawPlaceholder = false
            private set

        fun renderedText(): String = builder.toString()

        private fun track(value: String) {
            if (isPlaceholder(value)) sawPlaceholder = true
        }

        override fun renderValue(v: String) { track(v); builder.append(v) }
        override fun renderStringValue(v: String) { track(v); builder.append("\"$v\"") }
        override fun renderNumericValue(v: String) { builder.append(v) }
        override fun renderKeywordValue(v: String) { builder.append(v) }
        override fun renderValue(v: String, key: TextAttributesKey) { track(v); builder.append(v) }
        override fun renderStringValue(
            v: String,
            additionalSpecialCharsToHighlight: String?,
            maxLength: Int
        ) { track(v); builder.append("\"$v\"") }
        override fun renderComment(comment: String) { builder.append(" // $comment") }
        override fun renderSpecialSymbol(symbol: String) { builder.append(symbol) }
        override fun renderError(error: String) { builder.append("ERROR: $error") }
    }

    /**
     * Computes a value's presentation, waiting for asynchronous renderers (such as the JVM
     * debugger's debuggee-side toString()) to replace the "Collecting data…" placeholder.
     *
     * @return the final presentation; on timeout the latest known presentation (placeholder
     *         values are normalized to [PENDING_VALUE_TEXT]); null if nothing arrived at all.
     */
    suspend fun awaitPresentation(
        value: XValue,
        timeoutMs: Long = DEFAULT_PRESENTATION_TIMEOUT_MS,
        placeholderTexts: Set<String> = defaultPlaceholderTexts()
    ): ValuePresentation? {
        val node = AwaitableValueNode(placeholderTexts)
        val final = try {
            value.computePresentation(node, XValuePlace.TREE)
            withTimeoutOrNull(timeoutMs) { node.awaitFinal() }
        } finally {
            node.markObsolete()
        }
        if (final != null) return final
        val snapshot = node.latestSnapshot() ?: return null
        return if (snapshot.isPlaceholder) {
            snapshot.presentation.copy(value = PENDING_VALUE_TEXT)
        } else {
            snapshot.presentation
        }
    }
}
