package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.resume

/**
 * Collects a stack frame's variables in two stages: first the children list, then each
 * value's final presentation. One result slot per child guarantees no duplicates and a
 * stable order regardless of how many times each value updates its presentation.
 */
object FrameVariablesCollector {

    private const val CHILDREN_TIMEOUT_MS = 3000L
    private const val PRESENTATION_TIMEOUT_MS = 3000L

    suspend fun collectVariables(
        frame: XStackFrame,
        childrenTimeoutMs: Long = CHILDREN_TIMEOUT_MS,
        presentationTimeoutMs: Long = PRESENTATION_TIMEOUT_MS,
        placeholderTexts: Set<String> = VariablePresentationUtils.defaultPlaceholderTexts()
    ): List<VariableInfo> {
        val children = collectChildren(frame, childrenTimeoutMs) ?: return emptyList()
        return coroutineScope {
            children.map { (name, value) ->
                async {
                    // A single broken value (e.g. backend disposed mid-collection) must not
                    // cancel the sibling collections; degrade that one entry instead.
                    val presentation = try {
                        VariablePresentationUtils.awaitPresentation(value, presentationTimeoutMs, placeholderTexts)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    VariableInfo(
                        name = name,
                        value = presentation?.value ?: VariablePresentationUtils.UNAVAILABLE_VALUE_TEXT,
                        type = presentation?.type ?: "unknown",
                        hasChildren = presentation?.hasChildren ?: false
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * Resolves the frame's direct children, accumulating addChildren batches until last=true.
     * Returns null on timeout, an empty list when the frame reports an error.
     */
    suspend fun collectChildren(
        frame: XStackFrame,
        timeoutMs: Long = CHILDREN_TIMEOUT_MS
    ): List<Pair<String, XValue>>? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val collected = mutableListOf<Pair<String, XValue>>()
                val completed = AtomicBoolean(false)

                // On timeout/cancellation flip the flag so isObsolete() turns true and
                // well-behaved producers stop computing children for an abandoned request.
                continuation.invokeOnCancellation {
                    completed.set(true)
                }

                frame.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        val snapshot = synchronized(collected) {
                            for (i in 0 until children.size()) {
                                collected.add(children.getName(i) to children.getValue(i))
                            }
                            collected.toList()
                        }
                        if (last && completed.compareAndSet(false, true)) {
                            continuation.resume(snapshot)
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}

                    override fun setErrorMessage(errorMessage: String) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resume(emptyList())
                        }
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        setErrorMessage(errorMessage)
                    }

                    override fun setMessage(
                        message: String,
                        icon: Icon?,
                        attributes: SimpleTextAttributes,
                        link: XDebuggerTreeNodeHyperlink?
                    ) {}

                    // Still an abstract member of XCompositeNode even though deprecated, so it must
                    // be implemented; the real behaviour lives in the two-arg overload below.
                    @Deprecated("Deprecated in Java")
                    override fun tooManyChildren(remaining: Int) {}

                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {}

                    override fun isObsolete(): Boolean = completed.get()
                })
            }
        }
    }
}
