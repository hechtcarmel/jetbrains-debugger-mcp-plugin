package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions.RefreshAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the tool window's Refresh action against the defect it shipped with.
 *
 * ## The bug
 *
 * `RefreshAction` looked for `content.component is McpToolWindowPanel`, but
 * `McpToolWindowFactory.createToolWindowContent` registers a wrapper `JPanel` that holds the
 * toolbar and the panel. The `is` check was therefore never true, so the Refresh button did
 * nothing at all — in every release, for every user. Nothing failed, because nothing looked.
 *
 * ## Why this is a source check rather than a UI test
 *
 * Driving the real tool window means constructing `McpToolWindowPanel`, which eagerly resolves
 * `McpServerService` and does not survive the light test fixture. The cost of a full UI harness is
 * not worth it for a six-line action, but the *shape* of the bug — resolving the panel by testing
 * the content component directly instead of searching the container — is exactly what a source
 * check can pin, and it fails the moment someone reintroduces it.
 *
 * If a heavyweight tool-window fixture is ever added, replace this with a real reachability
 * assertion against the factory's registered content.
 */
class ToolWindowActionsTest {

    private val source: String =
        File("src/main/kotlin/com/github/hechtcarmel/jetbrainsdebuggermcpplugin/actions/RefreshAction.kt")
            .also { assertTrue("RefreshAction.kt not found at ${it.path} — has it moved?", it.isFile) }
            .readText()

    @Test
    fun `refresh action searches the container instead of testing the content component directly`() {
        assertTrue(
            "RefreshAction must walk the component tree to find McpToolWindowPanel. The registered " +
                "content is a wrapper JPanel, so resolving the panel with a direct `is` check on " +
                "content.component silently makes the Refresh button do nothing.",
            source.contains("is Container")
        )
        assertTrue(
            "The container search must recurse into child components",
            source.contains("component.components")
        )
    }

    /**
     * Overriding `update()` without `getActionUpdateThread()` has been an error since 2022.3.
     */
    @Test
    fun `refresh action declares its update thread`() {
        assertEquals(ActionUpdateThread.EDT, RefreshAction().actionUpdateThread)
    }
}
