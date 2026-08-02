package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport.McpHttpTestCase
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateExpressionSafetyMode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives the three breakpoint tools against a real project file and a real `XBreakpointManager`,
 * over real HTTP.
 *
 * These three are the only debugger tools that work without a live debuggee, which makes them the
 * one place tool *behaviour* — not just schema shape — can be verified in a headless test. Before
 * this file, `set_breakpoint`'s entire success path was uncovered: the only test that touched it
 * passed a nonexistent path and asserted the error branch.
 *
 * Assertions are made against the IDE's own breakpoint model rather than only against the tool's
 * JSON reply, because the reply is self-reported: `SetBreakpointResult.verified` is hardcoded
 * `true` and `status` hardcoded `"set"`, so a tool that created nothing at all would still return
 * a convincing success payload.
 */
class BreakpointToolsBehaviorTest : McpHttpTestCase() {

    private lateinit var javaFile: Path

    override fun setUp() {
        super.setUp()
        // Written to disk rather than through myFixture.addFileToProject: VirtualFileResolver
        // resolves via LocalFileSystem, which cannot see the in-memory TempFileSystem.
        javaFile = Path.of(requireNotNull(project.basePath), "src", "Sample.java")
        Files.createDirectories(javaFile.parent)
        Files.writeString(
            javaFile,
            """
            public class Sample {
                public static void main(String[] args) {
                    int total = 0;
                    for (int i = 0; i < 10; i++) {
                        total += i;
                    }
                    System.out.println(total);
                }
            }
            """.trimIndent()
        )
        LocalFileSystem.getInstance().refreshAndFindFileByPath(javaFile.toString())
            ?: throw AssertionError("Test file never became visible to the VFS: $javaFile")

        // The light fixture reuses one project across every method in the class, and breakpoints
        // live on the project's XBreakpointManager. Clearing on the way IN as well as out makes
        // each test independent of ordering.
        removeAllBreakpoints()
    }

    override fun tearDown() {
        try {
            removeAllBreakpoints()
            Files.deleteIfExists(javaFile)
        } finally {
            super.tearDown()
        }
    }

    private fun removeAllBreakpoints() {
        WriteAction.runAndWait<RuntimeException> {
            allLineBreakpoints().forEach { breakpointManager().removeBreakpoint(it) }
        }
    }

    private fun breakpointManager() = XDebuggerManager.getInstance(project).breakpointManager

    /**
     * Breakpoints for THIS test's file only.
     *
     * The light fixture shares state across test methods, so asserting on the manager's global
     * breakpoint count makes every assertion order-dependent. Scoping to the file under test is
     * both more robust and a more accurate statement of what each test actually claims.
     */
    private fun lineBreakpoints() = breakpointManager().allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .filter { it.fileUrl.endsWith("Sample.java") }

    private fun allLineBreakpoints() =
        breakpointManager().allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()

    private fun callTool(name: String, arguments: String) =
        pumpingEdt { post(McpConstants.MCP_ENDPOINT_PATH, toolCall(name, arguments)) }
            .jsonBody()["result"]!!.jsonObject

    private fun setBreakpoint(extra: String = "", line: Int = 5) = callTool(
        "set_breakpoint",
        """{"file_path":"$javaFile","line":$line$extra}"""
    )

    private fun assertSucceeded(result: kotlinx.serialization.json.JsonObject) {
        assertFalse(
            "Tool reported an error: ${result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content}",
            result["isError"]!!.jsonPrimitive.booleanOrNull!!
        )
    }

    private fun payload(result: kotlinx.serialization.json.JsonObject) =
        result["structuredContent"]!!.jsonObject

    // ── Creation ────────────────────────────────────────────────────────────────────────

    fun `test setting a breakpoint creates one in the IDE at the requested line`() {
        val result = setBreakpoint(line = 5)
        assertSucceeded(result)

        val created = lineBreakpoints().single()
        assertEquals(
            "The tool takes a 1-based line and the IDE stores 0-based",
            4,
            created.line
        )
        assertTrue("Breakpoint must point at the test file", created.fileUrl.endsWith("Sample.java"))
        assertTrue("A newly created breakpoint is enabled", created.isEnabled)

        assertEquals("set", payload(result)["status"]!!.jsonPrimitive.content)
        assertEquals(5, payload(result)["line"]!!.jsonPrimitive.content.toInt())
    }

    fun `test a condition reaches the IDE breakpoint`() {
        assertSucceeded(setBreakpoint(""","condition":"i == 7""""))

        assertEquals("i == 7", lineBreakpoints().single().conditionExpression?.expression)
    }

    /**
     * `{expression}` placeholders are rewritten to language-specific syntax before being stored —
     * for a `.java` file that is string concatenation.
     */
    fun `test a log message is transformed to Java syntax before being stored`() {
        assertSucceeded(setBreakpoint(""","log_message":"total={total}""""))

        val stored = lineBreakpoints().single().logExpressionObject?.expression
        assertNotNull("log_message must reach the breakpoint", stored)
        assertEquals(""""total=" + (total)""", stored)
    }

    fun `test suspend policy none creates a non-stopping logpoint`() {
        assertSucceeded(setBreakpoint(""","suspend_policy":"none","log_message":"hit""""))

        assertEquals(SuspendPolicy.NONE, lineBreakpoints().single().suspendPolicy)
    }

    fun `test suspend policy thread suspends only the current thread`() {
        assertSucceeded(setBreakpoint(""","suspend_policy":"thread""""))

        assertEquals(SuspendPolicy.THREAD, lineBreakpoints().single().suspendPolicy)
    }

    fun `test enabled false creates a disabled breakpoint`() {
        assertSucceeded(setBreakpoint(""","enabled":false"""))

        assertFalse(lineBreakpoints().single().isEnabled)
    }

    // ── Idempotence and update semantics ────────────────────────────────────────────────

    /**
     * Setting the same location twice updates in place rather than stacking duplicates.
     */
    fun `test setting the same line twice does not create a second breakpoint`() {
        assertSucceeded(setBreakpoint(line = 5))
        assertSucceeded(setBreakpoint(line = 5))

        assertEquals(1, lineBreakpoints().size)
    }

    /**
     * Documented, slightly surprising behaviour: `enabled` is applied unconditionally on every
     * call, so re-setting a disabled breakpoint silently re-enables it, while `condition` is only
     * applied when supplied and therefore can never be cleared. Pinned so a refactor toward
     * "apply all fields uniformly" is a deliberate decision rather than drift.
     */
    fun `test re-setting re-enables a disabled breakpoint but cannot clear its condition`() {
        assertSucceeded(setBreakpoint(""","enabled":false,"condition":"i == 1""""))
        assertFalse(lineBreakpoints().single().isEnabled)

        assertSucceeded(setBreakpoint(line = 5))

        val breakpoint = lineBreakpoints().single()
        assertTrue("Omitting `enabled` re-enables — it defaults to true rather than being left alone", breakpoint.isEnabled)
        assertEquals(
            "Omitting `condition` leaves the previous condition in place; there is no way to clear it",
            "i == 1",
            breakpoint.conditionExpression?.expression
        )
    }

    // ── Listing ─────────────────────────────────────────────────────────────────────────

    fun `test list_breakpoints reports what was set`() {
        assertSucceeded(setBreakpoint(line = 5, extra = ""","condition":"i > 3""""))

        val listed = payload(callTool("list_breakpoints", "{}"))["breakpoints"]!!.jsonArray
            .filter { it.jsonObject["file"]!!.jsonPrimitive.content.endsWith("Sample.java") }
        assertEquals(1, listed.size)

        val entry = listed.single().jsonObject
        assertEquals(5, entry["line"]!!.jsonPrimitive.content.toInt())
        assertEquals("i > 3", entry["condition"]!!.jsonPrimitive.content)
        assertTrue(entry["file"]!!.jsonPrimitive.content.endsWith("Sample.java"))
    }

    fun `test list_breakpoints reports nothing for a file with no breakpoints`() {
        val listed = payload(callTool("list_breakpoints", "{}"))["breakpoints"]!!.jsonArray
            .filter { it.jsonObject["file"]!!.jsonPrimitive.content.endsWith("Sample.java") }

        assertEquals(emptyList<Any>(), listed)
    }

    // ── Stable IDs ──────────────────────────────────────────────────────────────────────
    //
    // Breakpoint IDs are opaque strings minted per object by StableObjectIds (D8). What clients
    // rely on is agreement: every tool must report the same ID for the same breakpoint, and
    // re-setting a line that updates in place must not mint a new identity.

    fun `test set_breakpoint and list_breakpoints agree on the breakpoint id`() {
        val setId = payload(setBreakpoint(line = 5))["breakpointId"]!!.jsonPrimitive.content

        val listedId = payload(callTool("list_breakpoints", "{}"))["breakpoints"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["file"]?.jsonPrimitive?.content?.endsWith("Sample.java") == true }["id"]!!
            .jsonPrimitive.content

        assertEquals(setId, listedId)
    }

    fun `test re-setting the same line reports the same breakpoint id`() {
        val first = payload(setBreakpoint(line = 5))["breakpointId"]!!.jsonPrimitive.content
        val second = payload(setBreakpoint(line = 5))["breakpointId"]!!.jsonPrimitive.content

        assertEquals("An in-place update must keep the breakpoint's identity", first, second)
    }

    // ── Removal ─────────────────────────────────────────────────────────────────────────

    fun `test remove_breakpoint deletes it from the IDE`() {
        val breakpointId = payload(setBreakpoint(line = 5))["breakpointId"]!!.jsonPrimitive.content
        assertEquals(1, lineBreakpoints().size)

        assertSucceeded(callTool("remove_breakpoint", """{"breakpoint_id":"$breakpointId"}"""))

        assertEquals("The breakpoint must be gone from the IDE, not just from the reply", 0, lineBreakpoints().size)
    }

    fun `test removing an unknown breakpoint id fails`() {
        val result = callTool("remove_breakpoint", """{"breakpoint_id":"not-a-real-id"}""")

        assertTrue(result["isError"]!!.jsonPrimitive.booleanOrNull!!)
    }

    // ── Safety guard ────────────────────────────────────────────────────────────────────
    //
    // Conditions and log expressions are evaluated by the debugger on every hit, unattended, so
    // they consult the same safety guard as evaluate_expression. The mode setting is app-level
    // and shared across tests in the fixture; always restore it.

    private fun withSafetyMode(mode: EvaluateExpressionSafetyMode, block: () -> Unit) {
        val settings = McpSettings.getInstance()
        val previous = settings.evaluateExpressionSafetyMode
        settings.evaluateExpressionSafetyMode = mode
        try {
            block()
        } finally {
            settings.evaluateExpressionSafetyMode = previous
        }
    }

    fun `test a process execution condition is rejected under read-only mode`() {
        withSafetyMode(EvaluateExpressionSafetyMode.READ_ONLY) {
            val result = setBreakpoint(""","condition":"Runtime.getRuntime().exec(\"id\")"""")

            assertTrue(result["isError"]!!.jsonPrimitive.booleanOrNull!!)
            val message = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue("Should name the blocked construct, was: $message", message.contains("process execution"))
            assertTrue("Should identify the condition as the culprit, was: $message", message.contains("condition"))
            assertEquals("Nothing must be created on the rejection path", 0, lineBreakpoints().size)
        }
    }

    fun `test a process execution log message placeholder is rejected under read-only mode`() {
        withSafetyMode(EvaluateExpressionSafetyMode.READ_ONLY) {
            val result = setBreakpoint(""","log_message":"pid={Runtime.getRuntime().exec(\"id\")}"""")

            assertTrue(result["isError"]!!.jsonPrimitive.booleanOrNull!!)
            val message = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue("Should name the blocked construct, was: $message", message.contains("process execution"))
            assertTrue("Should identify the log_message as the culprit, was: $message", message.contains("log_message"))
            assertEquals("Nothing must be created on the rejection path", 0, lineBreakpoints().size)
        }
    }

    fun `test a benign condition still reaches the breakpoint under the default blocklist`() {
        withSafetyMode(EvaluateExpressionSafetyMode.DEFAULT_BLOCKLIST) {
            assertSucceeded(setBreakpoint(""","condition":"i == 7""""))

            assertEquals("i == 7", lineBreakpoints().single().conditionExpression?.expression)
        }
    }

    // ── Error paths ─────────────────────────────────────────────────────────────────────

    fun `test setting a breakpoint in a missing file explains the JAR syntax`() {
        val result = callTool("set_breakpoint", """{"file_path":"/nope/Missing.java","line":1}""")

        assertTrue(result["isError"]!!.jsonPrimitive.booleanOrNull!!)
        val message = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Should name the missing file, was: $message", message.contains("/nope/Missing.java"))
        assertTrue("Should explain the JAR '!/' form, was: $message", message.contains("!/"))
        assertEquals("Nothing must be created on the failure path", 0, lineBreakpoints().size)
    }

    fun `test missing required parameters are reported by name`() {
        listOf(
            """{"line":5}""" to "file_path",
            """{"file_path":"$javaFile"}""" to "line",
        ).forEach { (arguments, expectedName) ->
            val result = callTool("set_breakpoint", arguments)

            assertTrue("Omitting $expectedName must fail", result["isError"]!!.jsonPrimitive.booleanOrNull!!)
            assertEquals(
                "Missing required parameter: $expectedName",
                result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            )
        }
    }
}
