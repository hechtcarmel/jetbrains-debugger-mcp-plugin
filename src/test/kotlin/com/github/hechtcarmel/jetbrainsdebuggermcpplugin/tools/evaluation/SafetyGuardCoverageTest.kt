package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Guards the *reach* of [EvaluateExpressionSafetyGuard], as opposed to its decision table
 * (which [EvaluateExpressionSafetyGuardTest] covers).
 *
 * ## The bug this exists to prevent
 *
 * `set_variable` used to build `"$variableName = $newValue"` and hand it straight to the same
 * `XDebuggerEvaluator` that `evaluate_expression` uses — without ever calling the guard. Because
 * `new_value` is evaluated as a code fragment, an agent could run
 *
 * ```json
 * {"variable_name": "x", "new_value": "Runtime.getRuntime().exec(\"...\")"}
 * ```
 *
 * and execute arbitrary processes even with the safety mode set to Read-only. The blocklist was
 * one tool wide.
 *
 * A guard is only as good as the number of call sites that use it, and a call site is trivially
 * lost in a refactor, so the source-level check below is deliberate: it fails when a *new*
 * evaluator entry point appears without a guard call, which no behavioural test can do.
 */
class SafetyGuardCoverageTest {

    private companion object {
        val TOOLS_DIR = File("src/main/kotlin/com/github/hechtcarmel/jetbrainsdebuggermcpplugin/tools")

        /**
         * Tools that hand user-controlled text to an [com.intellij.xdebugger.evaluation.XDebuggerEvaluator]
         * and must therefore consult the guard first.
         */
        val MUST_CONSULT_GUARD = setOf(
            "evaluation/EvaluateTool.kt",
            "variable/SetVariableTool.kt",
        )
    }

    private fun source(relativePath: String): String {
        val file = File(TOOLS_DIR, relativePath)
        assertTrue("Expected tool source at ${file.path} — has it moved?", file.isFile)
        return file.readText()
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }

    @Test
    fun `every tool that evaluates user input consults the safety guard`() {
        val missing = MUST_CONSULT_GUARD
            .filterNot { source(it).contains("EvaluateExpressionSafetyGuard.validate") }
            .sorted()

        assertEquals(
            "These tools evaluate user-supplied text but never call " +
                "EvaluateExpressionSafetyGuard.validate, so the blocklist and read-only mode do " +
                "not apply to them.",
            emptyList<String>(),
            missing
        )
    }

    /**
     * Catches a *new* evaluator call site added without a guard call — the shape of the original
     * bug.
     *
     * Every tool that evaluates user input does so through `EvaluatorUtils`, so its call sites are
     * the complete set of places a payload can reach the debugger. `EvaluatorUtils` itself is the
     * shared helper and is excluded: it never sees a safety mode, and guarding inside it would put
     * the check below the layer that knows the source position.
     */
    @Test
    fun `the set of tools that evaluate user input is exactly the guarded set`() {
        val evaluating = TOOLS_DIR.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "EvaluatorUtils.kt" }
            .filter { it.readText().contains("EvaluatorUtils.") }
            .map { it.relativeTo(TOOLS_DIR).path.replace(File.separatorChar, '/') }
            .sorted()
            .toSet()

        assertEquals(
            "A tool started evaluating user-supplied text. Route it through " +
                "EvaluateExpressionSafetyGuard.validate and add it to MUST_CONSULT_GUARD — " +
                "otherwise the blocklist and read-only mode silently do not apply to it.",
            MUST_CONSULT_GUARD.sorted(),
            evaluating.sorted()
        )
    }

    /**
     * The blocklist applies in every mode except Unrestricted, so a value carrying a process-exec
     * payload is refused regardless of whether the user picked Guarded or Read-only.
     */
    @Test
    fun `a process execution payload is blocked in every non-unrestricted mode`() {
        val payload = """Runtime.getRuntime().exec("open -a Calculator")"""

        EvaluateExpressionSafetyMode.entries
            .filter { it != EvaluateExpressionSafetyMode.UNRESTRICTED }
            .forEach { mode ->
                val violation = EvaluateExpressionSafetyGuard.validate(payload, mode, context = null)
                assertNotNull("$mode must block a process-execution payload", violation)
                assertEquals("process-execution", violation!!.ruleId)
            }
    }

    /**
     * A plain literal — by far the most common `set_variable` value — must still be allowed once
     * the guard is in the path, otherwise the fix would break the tool's normal use.
     */
    @Test
    fun `an ordinary literal value is allowed in every mode`() {
        listOf("42", "\"hello\"", "true", "null", "-1.5").forEach { literal ->
            EvaluateExpressionSafetyMode.entries.forEach { mode ->
                assertNull(
                    "$mode must allow the ordinary value $literal, or set_variable becomes unusable",
                    EvaluateExpressionSafetyGuard.validate(literal, mode, context = null)
                )
            }
        }
    }
}
