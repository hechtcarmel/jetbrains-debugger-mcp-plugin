package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the threading and cancellation conventions for the tool layer at the source level.
 *
 * ## Why these rules exist
 *
 * - **No `invokeAndWait` in suspend code.** `Application.invokeAndWait` dispatches with
 *   `ModalityState.defaultModalityState()`, which ignores the `ModalityState.any()` context
 *   element `KtorMcpServer` installs around every tool call. With any modal dialog open in the
 *   IDE, the runnable queues until the user closes it and the tool call hangs. The sanctioned
 *   idiom is `AbstractMcpTool.onEdt` / `withContext(Dispatchers.EDT)`, which reads the
 *   coroutine's modality context.
 * - **No `Dispatchers.Main`.** In the IntelliJ runtime, `Dispatchers.Main` resolves to an EDT
 *   dispatcher whose locking behaviour depends on a JVM flag; `Dispatchers.EDT` is the
 *   platform-defined dispatcher with stable semantics.
 * - **No blocking `ReadAction.compute` in `AbstractMcpTool`.** The shared `readAction` helper
 *   must stay on the suspending, cancellable `com.intellij.openapi.application.readAction`,
 *   which yields to pending write actions instead of stalling the EDT.
 * - **Cancellation must propagate.** `ProcessCanceledException` extends
 *   `java.util.concurrent.CancellationException` (verified against 2025.2), so a
 *   `catch (e: Exception)` around cancellable code converts a cancellation into a fake
 *   `isError: true` MCP result and breaks structured concurrency. Every such handler must be
 *   preceded by a `catch (e: CancellationException)` that rethrows.
 *
 * ## Why a source check
 *
 * The suite has no live debug session (see CLAUDE.md "Known gaps"), so the paused-state code
 * paths where these bugs bite are unreachable in tests. The convention itself is what a source
 * check can pin, following the precedent of `ToolWindowActionsTest`.
 *
 * ## Scope
 *
 * Currently limited to the files migrated in the SDK-migration hardening passes. Widen the list
 * as the remaining tool files (`SessionStatusCollector`, safety guard) are migrated to the same
 * idioms.
 */
class ThreadingConventionsTest {

    private val toolsRoot = File("src/main/kotlin/com/github/hechtcarmel/jetbrainsdebuggermcpplugin/tools")

    private val migratedDirs = listOf("execution", "runconfig", "variable", "navigation", "breakpoint")

    private val migratedFiles = listOf(
        "AbstractMcpTool.kt",
        "session/StartDebugSessionTool.kt",
        "session/StopDebugSessionTool.kt",
        "stack/SelectStackFrameTool.kt",
        "evaluation/EvaluateTool.kt",
        "util/EvaluatorUtils.kt"
    )

    private fun migratedSources(): Map<String, String> {
        val files = migratedDirs.flatMap { dir ->
            File(toolsRoot, dir)
                .also { assertTrue("Directory not found: ${it.path} — has it moved?", it.isDirectory) }
                .listFiles { f -> f.extension == "kt" }!!.toList()
        } + migratedFiles.map { rel ->
            File(toolsRoot, rel).also { assertTrue("File not found: ${it.path} — has it moved?", it.isFile) }
        }
        assertTrue("Expected to scan at least 12 files, found ${files.size}", files.size >= 12)
        return files.associate { it.name to stripComments(it.readText()) }
    }

    /** KDoc is allowed to mention the anti-patterns; only code is scanned. */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun `migrated tools never call invokeAndWait`() {
        for ((name, source) in migratedSources()) {
            assertTrue(
                "$name calls invokeAndWait, which uses defaultModalityState and hangs behind " +
                    "modal dialogs. Use onEdt { } / withContext(Dispatchers.EDT) instead.",
                !source.contains("invokeAndWait")
            )
        }
    }

    @Test
    fun `migrated tools never use Dispatchers Main`() {
        for ((name, source) in migratedSources()) {
            assertTrue(
                "$name uses Dispatchers.Main, whose locking semantics depend on a JVM flag. " +
                    "Use Dispatchers.EDT (com.intellij.openapi.application.EDT).",
                !source.contains("Dispatchers.Main")
            )
        }
    }

    @Test
    fun `every broad exception handler is preceded by a cancellation rethrow`() {
        for ((name, source) in migratedSources()) {
            val broadCatches = Regex("""catch\s*\(\w+:\s*Exception\)""").findAll(source).count()
            val cancellationRethrows = Regex(
                """catch\s*\(\w+:\s*CancellationException\)\s*\{[^}]*throw\s""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(source).count()
            assertTrue(
                "$name has $broadCatches `catch (e: Exception)` but only $cancellationRethrows " +
                    "rethrowing `catch (e: CancellationException)`. ProcessCanceledException " +
                    "extends CancellationException — swallowing it turns a cancelled call into a " +
                    "fake isError result. Rethrow cancellation first (see FrameVariablesCollector).",
                cancellationRethrows >= broadCatches
            )
        }
    }

    @Test
    fun `AbstractMcpTool readAction is the suspending cancellable variant`() {
        val source = stripComments(File(toolsRoot, "AbstractMcpTool.kt").readText())
        assertTrue(
            "AbstractMcpTool.readAction must be suspend and delegate to the platform's " +
                "cancellable com.intellij.openapi.application.readAction.",
            source.contains("protected suspend fun <T> readAction") &&
                source.contains("com.intellij.openapi.application.readAction(action)")
        )
        assertEquals(
            "AbstractMcpTool must not use the blocking ReadAction.compute",
            false,
            source.contains("ReadAction.compute")
        )
    }

    @Test
    fun `step_out reports failures like its sibling step tools`() {
        val source = File(toolsRoot, "execution/StepOutTool.kt").readText()
        assertTrue(
            "StepOutTool must wrap the EDT hop in the same try/catch as step_over/step_into and " +
                "report failures as 'Failed to step out: ...'.",
            source.contains("Failed to step out")
        )
    }
}
