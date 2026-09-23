package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.livedebug

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.runWithIdeModality
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.SetBreakpointTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.JumpToLineTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.PydevdSetNextStatement
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.ResumeTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.StepOverTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.WaitForPauseTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.GetDebugSessionStatusTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.StartDebugSessionTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.StopDebugSessionTool
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.python.run.PythonConfigurationType
import com.jetbrains.python.run.PythonRunConfiguration
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.getOrCreateAdditionalData
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `jump_to_line` against a **real pydevd session** — the Python counterpart of [LiveDebugSessionTest].
 * Every jump outcome the tool distinguishes is driven through the genuine IDE → pydevd → CPython path:
 * a backward jump that re-runs code, a forward jump that skips it, a jump inside a called function,
 * CPython's and pydevd's refusals, a stop that cannot be jumped from, and the checks the tool makes
 * itself (wrong file, session not paused).
 *
 * ## Infrastructure this test stands up
 *
 * - **The Python plugin (PythonCore) on the test classpath only** — `platformTestPlugins` in
 *   `gradle.properties`. Production code has no Python dependency; the tool finds pydevd reflectively.
 * - **A CPython interpreter**: `MCP_LIVE_PYTHON` (CI sets it via `actions/setup-python`), else
 *   `python3` on the PATH. There is no skip: a missing interpreter fails the test, because a skipped
 *   live suite would look exactly like a passing one.
 * - **A Python SDK** registered with only a home path, a version string and flavor data — no
 *   `PythonSdkUpdater` run, so no packaging refresh or skeleton generation.
 * - **An ordinary Python run configuration**, started through `start_debug_session`. The IDE listens
 *   on a loopback port and launches `helpers/pydev/pydevd.py --client ...`, exactly as for a user's
 *   debug run. pydevd executes no user code until the IDE sends its "run" command, which keeps the
 *   first pause deterministic.
 * - **`idea.python.helpers.path`** pinned to the plugin's `helpers` directory: in a Gradle test the
 *   plugin is loaded from Gradle's transform cache rather than an IDE install.
 *
 * The debuggee sleeps for a second before calling anything with a breakpoint: PyCharm sends its
 * breakpoint commands from pooled threads without waiting for them, so its "run" command can overtake
 * them.
 */
class LivePythonDebugSessionTest : JavaCodeInsightFixtureTestCase() {

    companion object {
        private const val HELPERS_PROPERTY = "idea.python.helpers.path"

        private val SOURCE = """
            import time

            time.sleep(1)  # PyCharm registers breakpoints asynchronously; let it finish first


            def scale(value):
                value = value * 2  # FN_FIRST
                value = value + 100  # FN_SKIPPED
                return value  # FN_RETURN


            def main():
                total = 0  # RESET
                for i in range(3):  # LOOP_HEAD
                    total += i  # LOOP_BODY
                total = total + 10  # BREAKPOINT
                doubled = total * 2  # AFTER
                result = scale(total)  # CALL
                print("done", doubled, result, flush=True)  # PRINT
                time.sleep(120)  # PARK


            main()
        """.trimIndent()

        private val FN_FIRST_LINE = markerLine("# FN_FIRST")
        private val FN_SKIPPED_LINE = markerLine("# FN_SKIPPED")
        private val FN_RETURN_LINE = markerLine("# FN_RETURN")
        private val RESET_LINE = markerLine("# RESET")
        private val LOOP_HEAD_LINE = markerLine("# LOOP_HEAD")
        private val LOOP_BODY_LINE = markerLine("# LOOP_BODY")
        private val BREAKPOINT_LINE = markerLine("# BREAKPOINT")
        private val AFTER_LINE = markerLine("# AFTER")
        private val CALL_LINE = markerLine("# CALL")
        private val PRINT_LINE = markerLine("# PRINT")

        private fun markerLine(marker: String): Int {
            val index = SOURCE.lines().indexOfFirst { it.contains(marker) }
            check(index >= 0) { "Marker $marker not found in debuggee source" }
            return index + 1
        }
    }

    /** pydevd's reader threads and the debuggee's process-handler threads outlive a single tool call. */
    private val threadWhitelist = Disposer.newDisposable("LivePythonDebugSessionTest threads")
    private var sdk: Sdk? = null
    private var previousHelpersPath: String? = null

    override fun setUp() {
        super.setUp()
        ThreadLeakTracker.longRunningThreadCreated(threadWhitelist, "")
        previousHelpersPath = System.getProperty(HELPERS_PROPERTY)
    }

    override fun tearDown() {
        try {
            stopAnyDebugSessions()
        } finally {
            try {
                sdk?.let { registered ->
                    WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(registered) }
                }
                val previous = previousHelpersPath
                if (previous != null) System.setProperty(HELPERS_PROPERTY, previous)
                else System.clearProperty(HELPERS_PROPERTY)
            } finally {
                try {
                    super.tearDown()
                } finally {
                    // The fixture checks for leaked threads during super.tearDown(); keep the whitelist until then.
                    Disposer.dispose(threadWhitelist)
                }
            }
        }
    }

    fun `test jump_to_line moves a real pydevd session backward and forward and inside a function`() {
        System.setProperty(HELPERS_PROPERTY, locatePythonHelpers().toString())
        val script = myFixture.addFileToProject("jump_target.py", SOURCE).virtualFile.path
        val otherFile = myFixture.addFileToProject("other_module.py", "value = 1\n").virtualFile.path
        val configName = registerPythonConfiguration(script, registerPythonSdk())

        setBreakpoint(script, BREAKPOINT_LINE)
        setBreakpoint(script, FN_FIRST_LINE)
        setBreakpoint(script, PRINT_LINE)

        structured(runTool(StartDebugSessionTool(), buildJsonObject { put("configuration_name", configName) }))

        val paused = structured(runTool(WaitForPauseTool(), buildJsonObject { put("timeout", 90) }))
        assertEquals("Expected the breakpoint to be hit: $paused", "paused", paused.str("waitResult"))
        assertEquals(BREAKPOINT_LINE, paused["currentLocation"]!!.jsonObject["line"]!!.jsonPrimitive.int)
        assertEquals("0+1+2 accumulated before the breakpoint line", "3", evaluate("total"))

        // ── Refusals leave the execution point and the program state alone ──────────────────
        assertRefused(jump(script, LOOP_BODY_LINE), "Cannot jump to $script:$LOOP_BODY_LINE: ", "for loop")
        assertRefused(jump(script, FN_SKIPPED_LINE), "jump is available only within the bottom frame")
        assertRefused(jump(otherFile, 1), "must be in the file of the current execution point")
        assertEquals(BREAKPOINT_LINE, currentLine())
        assertEquals("3", evaluate("total"))

        // ── Backward: the jump runs nothing; the jumped-to line runs again when stepped ─────
        assertJumped(jump(script, RESET_LINE), RESET_LINE)
        assertEquals("The jump itself must execute nothing", "3", evaluate("total"))
        structured(runTool(StepOverTool(), buildJsonObject {}))
        awaitPauseAtLine(LOOP_HEAD_LINE)
        assertEquals("`total = 0` must have run again", "0", evaluate("total"))
        structured(runTool(ResumeTool(), buildJsonObject {}))
        awaitPauseAtLine(BREAKPOINT_LINE)
        assertEquals("The loop must have run again", "3", evaluate("total"))

        // ── Forward: the skipped line never runs ────────────────────────────────────────────
        assertJumped(jump(script, AFTER_LINE), AFTER_LINE)
        structured(runTool(StepOverTool(), buildJsonObject {}))
        awaitPauseAtLine(CALL_LINE)
        assertEquals("`total = total + 10` must have been skipped", "6", evaluate("doubled"))

        // ── Inside a called function ────────────────────────────────────────────────────────
        structured(runTool(ResumeTool(), buildJsonObject {}))
        awaitPauseAtLine(FN_FIRST_LINE)
        assertEquals("3", evaluate("value"))
        assertJumped(jump(script, FN_RETURN_LINE), FN_RETURN_LINE)

        // ── A stop that is not at the start of a line cannot be jumped from ────────────────
        // Stepping past `return` stops back on the caller's line on a 'return' event, which pydevd
        // refuses to move without giving a reason.
        structured(runTool(StepOverTool(), buildJsonObject {}))
        awaitPauseAtLine(CALL_LINE)
        assertRefused(jump(script, PRINT_LINE), PydevdSetNextStatement.NOT_AT_LINE_START_REASON)

        structured(runTool(ResumeTool(), buildJsonObject {}))
        awaitPauseAtLine(PRINT_LINE)
        assertEquals("`value * 2` and `value + 100` must have been skipped", "3", evaluate("result"))

        // ── A running session is refused before pydevd is ever asked ──────────────────────
        structured(runTool(ResumeTool(), buildJsonObject {}))
        waitUntil("the debuggee to run again") { sessionState() == "running" }
        assertRefused(jump(script, RESET_LINE), "Session must be paused to jump to line")

        structured(runTool(StopDebugSessionTool(), buildJsonObject {}))
        waitUntil("the debug session to leave XDebuggerManager") {
            XDebuggerManager.getInstance(project).debugSessions.isEmpty()
        }
    }

    // ── jump_to_line ────────────────────────────────────────────────────────────────────

    private fun jump(filePath: String, line: Int): CallToolResult =
        runTool(JumpToLineTool(), buildJsonObject {
            put("file_path", filePath)
            put("line", line)
        })

    private fun assertJumped(result: CallToolResult, line: Int) {
        val payload = structured(result)
        assertEquals("jump_to_line", payload.str("action"))
        assertEquals("paused", payload.str("newState"))
        val message = payload.str("message")
        assertTrue(
            "Expected the jump to land on line $line, got: $message",
            message.startsWith("Execution point moved to ") && message.contains(":$line.")
        )
        assertEquals("jump_to_line must return only once the session reports the new line", line, currentLine())
    }

    private fun assertRefused(result: CallToolResult, vararg fragments: String) {
        val text = resultText(result)
        assertTrue("Expected jump_to_line to refuse, got: $text", result.isError == true)
        fragments.forEach { fragment ->
            assertTrue("Expected the refusal to contain \"$fragment\", got: $text", text.contains(fragment))
        }
    }

    // ── Session inspection ──────────────────────────────────────────────────────────────

    private fun evaluate(expression: String): String {
        val result = structured(runTool(EvaluateTool(), buildJsonObject {
            put("expression", expression)
        }))["result"]!!.jsonObject
        assertEquals("Evaluating $expression must not fail: $result", JsonNull, result["error"])
        return result.str("value")
    }

    private fun status(): JsonObject =
        structured(runTool(GetDebugSessionStatusTool(), buildJsonObject {
            put("include_variables", false)
            put("include_source_context", false)
        }))

    private fun currentLine(): Int? =
        status()["currentLocation"]?.let { location ->
            (location as? JsonObject)?.get("line")?.jsonPrimitive?.int
        }

    private fun sessionState(): String = status().str("state")

    private fun setBreakpoint(filePath: String, line: Int) {
        val payload = structured(runTool(SetBreakpointTool(), buildJsonObject {
            put("file_path", filePath)
            put("line", line)
        }))
        assertEquals("Breakpoint at line $line must be set: $payload", "set", payload.str("status"))
    }

    /**
     * Steps and resumes return as soon as they are *initiated*; poll `wait_for_pause` until the
     * reported line is the expected one (as `LiveDebugSessionTest.awaitPauseAtLine`).
     */
    private fun awaitPauseAtLine(expectedLine: Int): JsonObject {
        val deadline = System.currentTimeMillis() + 60_000
        var last: JsonObject? = null
        while (System.currentTimeMillis() < deadline) {
            val status = structured(runTool(WaitForPauseTool(), buildJsonObject { put("timeout", 30) }))
            last = status
            assertEquals("Session died while waiting for line $expectedLine: $status", "paused", status.str("waitResult"))
            val line = status["currentLocation"]?.jsonObject?.get("line")?.jsonPrimitive?.int
            if (line == expectedLine) return status
            Thread.sleep(50)
        }
        throw AssertionError("Never paused on line $expectedLine; last status: $last")
    }

    // ── Python bootstrap ────────────────────────────────────────────────────────────────

    private val python: String by lazy {
        System.getenv("MCP_LIVE_PYTHON")?.takeIf { it.isNotBlank() }
            ?: listOf("python3", "python").firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it)?.path }
            ?: throw AssertionError(
                "No CPython found for the live pydevd test — set MCP_LIVE_PYTHON to a python3 executable " +
                    "(CI does this with actions/setup-python) or put python3 on the PATH"
            )
    }

    private fun pythonVersion(): String {
        val process = ProcessBuilder(python, "-c", "import platform; print(platform.python_version())")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue("$python did not report its version within 30s", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("$python failed to report its version: $output", 0, process.exitValue())
        return output
    }

    /**
     * Registers [python] as an SDK without running `PythonSdkUpdater`. The flavor data is created here,
     * on the EDT: creating it commits a write action, which must not happen lazily inside the run
     * configuration's validity check.
     */
    private fun registerPythonSdk(): Sdk {
        allowPythonInstallAccess()
        val created = ProjectJdkTable.getInstance().createSdk("mcp-live-python", PythonSdkType.getInstance())
        val modificator = created.sdkModificator
        modificator.homePath = python
        modificator.versionString = "Python ${pythonVersion()}"
        WriteAction.run<Throwable> { modificator.commitChanges() }
        created.getOrCreateAdditionalData()
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().addJdk(created) }
        sdk = created
        return created
    }

    /**
     * Platform tests may only touch whitelisted directories, and PyCharm's flavor detection looks
     * around the interpreter's installation (e.g. for `conda-meta`). Allow its prefix — `<prefix>/bin/python`
     * — both as given and with symlinks resolved, for this test only.
     */
    private fun allowPythonInstallAccess() {
        val executable = Path.of(python)
        val prefixes = listOf(executable, executable.toRealPath())
            .mapNotNull { it.parent?.parent ?: it.parent }
            .map { it.toString() }
            .distinct()
        VfsRootAccess.allowRootAccess(testRootDisposable, *prefixes.toTypedArray())
    }

    private fun registerPythonConfiguration(scriptPath: String, sdk: Sdk): String {
        // Unique per invocation, as in LiveDebugSessionTest: the heavy fixture may reuse a project.
        val configurationName = "live-pydevd-${System.nanoTime()}"
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(configurationName, PythonConfigurationType.getInstance().factory)
        (settings.configuration as PythonRunConfiguration).apply {
            setScriptName(scriptPath)
            setWorkingDirectory(File(scriptPath).parent)
            setSdkHome(sdk.homePath)
            setSdk(sdk)
            setUseModuleSdk(false)
            // Pure-Python pydevd, whatever speedups the runner image might offer.
            setEnvs(linkedMapOf("PYDEVD_USE_CYTHON" to "NO"))
        }
        runManager.addConfiguration(settings)
        return configurationName
    }

    /** The PythonCore `helpers` directory — the one holding `pydev/pydevd.py` — next to the plugin's jars. */
    private fun locatePythonHelpers(): Path {
        val jar = Path.of(
            requireNotNull(PathManager.getJarPathForClass(PythonConfigurationType::class.java)) {
                "Cannot locate the jar of PythonConfigurationType — is PythonCore on the test classpath?"
            }
        )
        return generateSequence(jar.parent) { it.parent }
            .take(4)
            .map { it.resolve("helpers") }
            .firstOrNull { Files.isRegularFile(it.resolve("pydev/pydevd.py")) }
            ?: throw AssertionError("No helpers/pydev/pydevd.py found near the Python plugin jar $jar")
    }

    // ── Tool invocation (as in LiveDebugSessionTest) ─────────────────────────────────────

    private fun runTool(tool: McpTool, arguments: JsonObject, timeoutMs: Long = 120_000): CallToolResult {
        val future = CompletableFuture.supplyAsync {
            runBlocking { runWithIdeModality { tool.execute(myFixture.project, arguments) } }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        check(future.isDone) {
            "Timed out after ${timeoutMs}ms while pumping the EDT — ${tool.name} is most likely " +
                "blocked waiting on the EDT that this thread is pumping."
        }
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun structured(result: CallToolResult): JsonObject {
        assertFalse("Tool reported an error: ${resultText(result)}", result.isError == true)
        return requireNotNull(result.structuredContent) { "Tool returned no structuredContent: ${resultText(result)}" }
    }

    private fun resultText(result: CallToolResult): String =
        (result.content.firstOrNull() as? TextContent)?.text ?: "<no text content>"

    private fun JsonObject.str(key: String): String =
        requireNotNull(this[key]) { "Missing key '$key' in: $this" }.jsonPrimitive.content

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun stopAnyDebugSessions() {
        val fixture = myFixture ?: return
        val manager = XDebuggerManager.getInstance(fixture.project)
        if (manager.debugSessions.isEmpty()) return
        manager.debugSessions.forEach { runCatching { it.stop() } }
        waitUntil("leftover debug sessions to stop") {
            XDebuggerManager.getInstance(fixture.project).debugSessions.isEmpty()
        }
    }
}
