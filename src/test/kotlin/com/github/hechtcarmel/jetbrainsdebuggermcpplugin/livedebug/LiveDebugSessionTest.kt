package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.livedebug

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.runWithIdeModality
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.SetBreakpointTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.PauseTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.ResumeTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.StepOverTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution.WaitForPauseTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.ListDebugSessionsTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.StartDebugSessionTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session.StopDebugSessionTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.stack.GetStackTraceTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.variable.GetVariablesTool
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The one test that runs the debugger tools against a **real paused JVM** — the success paths of
 * `set_breakpoint` → `start_debug_session` → `wait_for_pause` → `get_variables` →
 * `evaluate_expression` → `step_over` → `get_stack_trace` → `stop_debug_session`, end to end.
 *
 * ## Infrastructure this test stands up
 *
 * - **Heavy Java project fixture** ([JavaCodeInsightFixtureTestCase]): a real on-disk project whose
 *   temp dir is registered as a *source root*. That matters twice: `VirtualFileResolver` resolves
 *   through `LocalFileSystem` (so the file must exist on real disk), and the Java debugger's
 *   `PositionManagerImpl` maps JVM classes back to sources through the class index (so the file
 *   must be in an indexed source root). The light fixture used elsewhere in the suite provides
 *   neither, which is why this class does not extend `McpHttpTestCase`. No module JDK is
 *   configured: breakpoint validation, position mapping and `total + 1` evaluation only need the
 *   PSI of the file itself.
 * - **An out-of-process debuggee**: the source is compiled with the test JVM's own in-process
 *   `javac` (`-g`, so the class file carries the local-variable table `get_variables` reads), then
 *   launched with the same JVM under `-agentlib:jdwp=...,server=y,suspend=y,address=127.0.0.1:0`.
 *   `suspend=y` makes the run deterministic — the VM does not execute `main` until the debugger
 *   attaches, so the breakpoint is always installed before the line runs. Port 0 lets the OS pick;
 *   the announced port is parsed from the agent's "Listening for transport" banner.
 * - **A `Remote` (attach) run configuration** pointing at that port, registered in [RunManager].
 *   Attaching instead of IDE-launching sidesteps the two things a headless fixture cannot provide:
 *   a project SDK and the JPS build. From `start_debug_session`'s point of view it is an ordinary
 *   named run configuration executed with the debug executor.
 *
 * Tool calls are invoked directly ([McpTool.execute]) rather than over HTTP — the HTTP edge has
 * its own conformance suite under `server/transport`, and what was uncovered until this test is
 * the debugger behaviour behind it.
 *
 * ## Threading
 *
 * Platform test methods run **on the EDT**, and every tool here hops onto the EDT internally, so
 * each call is executed on a pooled thread while this thread pumps the event queue — the same
 * pattern (and deadlock rationale) as `McpHttpTestCase.pumpingEdt`.
 */
class LiveDebugSessionTest : JavaCodeInsightFixtureTestCase() {

    companion object {
        private val SOURCE = """
            public class LiveDebugTarget {
                public static void main(String[] args) throws Exception {
                    int total = 0;
                    for (int i = 0; i < 5; i++) {
                        total += i;
                    }
                    int doubled = total * 2; // BREAKPOINT
                    System.out.println(doubled); // STEP_TARGET
                    Thread.sleep(60000L);
                }
            }
        """.trimIndent()

        private val BREAKPOINT_LINE = markerLine("// BREAKPOINT")
        private val STEP_TARGET_LINE = markerLine("// STEP_TARGET")

        private fun markerLine(marker: String): Int {
            val index = SOURCE.lines().indexOfFirst { it.contains(marker) }
            check(index >= 0) { "Marker $marker not found in debuggee source" }
            return index + 1
        }
    }

    private var debuggee: Process? = null
    private var classesDir: Path? = null

    override fun tearDown() {
        try {
            stopAnyDebugSessions()
        } finally {
            try {
                debuggee?.destroyForcibly()
                debuggee?.waitFor(10, TimeUnit.SECONDS)
                classesDir?.toFile()?.deleteRecursively()
            } finally {
                super.tearDown()
            }
        }
    }

    fun `test a real paused JVM is inspectable and steppable through the tools`() {
        val psiFile = myFixture.addFileToProject("LiveDebugTarget.java", SOURCE)
        val sourcePath = psiFile.virtualFile.path

        val port = launchDebuggee(compileDebuggee(sourcePath), "LiveDebugTarget")
        val configName = registerAttachConfiguration(port)

        // set_breakpoint before the session exists — it must be installed on attach.
        val setPayload = structured(runTool(SetBreakpointTool(), buildJsonObject {
            put("file_path", sourcePath)
            put("line", BREAKPOINT_LINE)
        }))
        assertEquals("set", setPayload.str("status"))
        val breakpointId = setPayload.str("breakpointId")

        structured(runTool(StartDebugSessionTool(), buildJsonObject {
            put("configuration_name", configName)
        }))

        // ── The breakpoint hit ──────────────────────────────────────────────────────────
        val paused = structured(runTool(WaitForPauseTool(), buildJsonObject { put("timeout", 60) }))
        assertEquals("Expected the breakpoint to be hit: $paused", "paused", paused.str("waitResult"))
        assertEquals("paused", paused.str("state"))
        assertEquals("breakpoint", paused.str("pausedReason"))

        val location = paused["currentLocation"]!!.jsonObject
        assertTrue(
            "Pause location must map back to the source file, was: ${location.str("file")}",
            location.str("file").endsWith("LiveDebugTarget.java")
        )
        assertEquals(BREAKPOINT_LINE, location["line"]!!.jsonPrimitive.int)

        val hit = paused["breakpointHit"]!!.jsonObject
        assertEquals(
            "wait_for_pause must attribute the pause to the breakpoint set_breakpoint created",
            breakpointId,
            hit.str("breakpointId")
        )

        assertTrue(
            "wait_for_pause's embedded variables must include the local, got: ${paused["variables"]}",
            paused["variables"]!!.jsonArray.any { it.jsonObject.str("name") == "total" }
        )

        // ── get_variables: the local has its real runtime value ─────────────────────────
        val variables = structured(runTool(GetVariablesTool(), buildJsonObject {}))["variables"]!!.jsonArray
        val total = variables.map { it.jsonObject }.singleOrNull { it.str("name") == "total" }
            ?: throw AssertionError("get_variables did not return 'total', got: $variables")
        assertEquals("0+1+2+3+4 accumulated before the breakpoint line", "10", total.str("value"))

        // ── evaluate_expression computes against live debuggee state ────────────────────
        val evaluation = structured(runTool(EvaluateTool(), buildJsonObject {
            put("expression", "total + 1")
        }))["result"]!!.jsonObject
        assertEquals("Evaluation must not report an error: $evaluation", JsonNull, evaluation["error"])
        assertEquals("11", evaluation.str("value"))

        // ── step_over advances exactly one line ─────────────────────────────────────────
        structured(runTool(StepOverTool(), buildJsonObject {}))
        val afterStep = awaitPauseAtLine(STEP_TARGET_LINE)
        assertEquals("step", afterStep.str("pausedReason"))

        // ── get_stack_trace has the main frame at the stepped-to position ───────────────
        val stack = structured(runTool(GetStackTraceTool(), buildJsonObject {}))
        val frames = stack["frames"]!!.jsonArray
        assertTrue("Expected at least the main frame, got: $stack", frames.size >= 1)
        val top = frames[0].jsonObject
        assertTrue(top["isCurrent"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            "Top frame must map to the source file, was: ${top["file"]}",
            top.str("file").endsWith("LiveDebugTarget.java")
        )
        assertEquals(STEP_TARGET_LINE, top["line"]!!.jsonPrimitive.int)

        // ── stop_debug_session detaches and the session disappears ──────────────────────
        structured(runTool(StopDebugSessionTool(), buildJsonObject {}))
        waitUntil("the debug session to leave XDebuggerManager") {
            XDebuggerManager.getInstance(myFixture.project).debugSessions.isEmpty()
        }
    }

    /**
     * The pause/resume golden path against a genuinely *running* JVM — the one scenario live-IDE
     * QA could not demonstrate, because real projects rarely have a config that stays up long
     * enough to interrupt (QA report §5/§8.4). A deliberate spin debuggee has no such problem.
     *
     * `pausedReason` is deliberately not asserted here: a manual pause has no breakpoint at the
     * pause site, so the documented file/line heuristic reports `step` — and the pause usually
     * lands inside `Thread.sleep`, where there is no source position at all.
     */
    fun `test a running JVM can be paused resumed and paused again through the tools`() {
        val spinSource = """
            public class LiveSpinTarget {
                public static void main(String[] args) throws Exception {
                    long ticks = 0;
                    while (true) {
                        ticks++;
                        Thread.sleep(10L);
                    }
                }
            }
        """.trimIndent()
        val psiFile = myFixture.addFileToProject("LiveSpinTarget.java", spinSource)

        val port = launchDebuggee(compileDebuggee(psiFile.virtualFile.path), "LiveSpinTarget")
        val configName = registerAttachConfiguration(port)

        structured(runTool(StartDebugSessionTool(), buildJsonObject {
            put("configuration_name", configName)
        }))

        // Registration of an attached session is asynchronous; poll through the tool layer (the
        // same machinery a client uses, and runTool pumps the EDT the registration needs).
        val sessionId = awaitRunningSessionId()

        // ── pause_execution suspends a free-running program ────────────────────────────
        // No session_id on purpose: a running session that has never paused is not the IDE's
        // "current" session, so this exercises resolveSession's only-session fallback — without
        // it, this exact call answered "No active debug session" (live-QA §5).
        val pause = structured(runTool(PauseTool(), buildJsonObject {}))
        assertEquals("Pause must succeed: ${'$'}pause", "success", pause.str("status"))

        val paused = structured(runTool(WaitForPauseTool(), buildJsonObject {
            put("session_id", sessionId)
            put("timeout", 30)
        }))
        assertEquals("Expected the session to report paused: ${'$'}paused", "paused", paused.str("waitResult"))
        assertEquals("paused", paused.str("state"))

        // ── resume_execution sets it running again ─────────────────────────────────────
        val resume = structured(runTool(ResumeTool(), buildJsonObject { put("session_id", sessionId) }))
        assertEquals("Resume must succeed: ${'$'}resume", "success", resume.str("status"))
        waitUntil("the session to be free-running again") {
            sessionState(sessionId) == "running"
        }

        // ── and the cycle is repeatable ────────────────────────────────────────────────
        structured(runTool(PauseTool(), buildJsonObject { put("session_id", sessionId) }))
        waitUntil("the second pause to land") {
            sessionState(sessionId) == "paused"
        }

        structured(runTool(StopDebugSessionTool(), buildJsonObject { put("session_id", sessionId) }))
        waitUntil("the debug session to leave XDebuggerManager") {
            XDebuggerManager.getInstance(myFixture.project).debugSessions.isEmpty()
        }
    }

    /** Polls `list_debug_sessions` until a session reports `running`, returning its id. */
    private fun awaitRunningSessionId(timeoutMs: Long = 30_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val sessions = structured(runTool(ListDebugSessionsTool(), buildJsonObject {}))["sessions"]!!.jsonArray
            val running = sessions.map { it.jsonObject }.firstOrNull { it.str("state") == "running" }
            if (running != null) return running.str("id")
            Thread.sleep(100)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for a running debug session")
    }

    private fun sessionState(sessionId: String): String? {
        val sessions = structured(runTool(ListDebugSessionsTool(), buildJsonObject {}))["sessions"]!!.jsonArray
        return sessions.map { it.jsonObject }.firstOrNull { it.str("id") == sessionId }?.str("state")
    }

    // ── Debuggee bootstrap ──────────────────────────────────────────────────────────────

    /**
     * The test JVM itself is the JetBrains Runtime, which ships without `jdk.compiler` — so both
     * `javac` and the debuggee's `java` come from a full JDK found on the host: `JAVA_HOME` first
     * (set in CI and required for the Gradle build anyway), the test JVM's own home as a fallback.
     * Using one home for both keeps the produced bytecode trivially runnable.
     */
    private val jdkHome: Path by lazy {
        listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"))
            .map { Path.of(it) }
            .firstOrNull { Files.isExecutable(it.resolve("bin/javac")) }
            ?: throw AssertionError(
                "No JDK with javac found — set JAVA_HOME to a full JDK " +
                    "(checked JAVA_HOME and java.home=${System.getProperty("java.home")})"
            )
    }

    private fun compileDebuggee(sourcePath: String): Path {
        val outDir = Files.createTempDirectory("live-debug-classes").also { classesDir = it }
        val javac = ProcessBuilder(
            jdkHome.resolve("bin/javac").toString(),
            "-g", // local-variable tables — without them get_variables has nothing to read
            "-d", outDir.toString(),
            sourcePath,
        ).redirectErrorStream(true).start()
        val output = javac.inputStream.bufferedReader().readText()
        assertTrue("javac did not finish within 60s", javac.waitFor(60, TimeUnit.SECONDS))
        assertEquals("javac failed:\n$output", 0, javac.exitValue())
        return outDir
    }

    /** Starts the debuggee suspended and returns the JDWP port the agent announces. */
    private fun launchDebuggee(classesDir: Path, mainClass: String): Int {
        val javaBinary = jdkHome.resolve("bin/java")
        val process = ProcessBuilder(
            javaBinary.toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
            "-cp", classesDir.toString(),
            mainClass,
        ).redirectErrorStream(true).start()
        debuggee = process

        val banner = Regex("""Listening for transport dt_socket at address:\s*(?:[\w.]+:)?(\d+)""")
        val seen = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine()
                ?: throw AssertionError(
                    "Debuggee exited (code ${process.waitFor()}) before announcing its JDWP port. Output:\n$seen"
                )
            seen.appendLine(line)
            banner.find(line)?.let { return it.groupValues[1].toInt() }
        }
        throw AssertionError("Debuggee never announced its JDWP port within 15s. Output:\n$seen")
    }

    private fun registerAttachConfiguration(port: Int): String {
        // Unique per invocation: the heavy fixture can reuse one project across test methods, and
        // a name collision would let start_debug_session resolve a stale config with a dead port.
        val configurationName = "live-debuggee-$port"
        val type = ConfigurationTypeUtil.findConfigurationType(RemoteConfigurationType::class.java)
        val runManager = RunManager.getInstance(myFixture.project)
        val settings = runManager.createConfiguration(configurationName, type.configurationFactories.first())
        (settings.configuration as RemoteConfiguration).apply {
            HOST = "127.0.0.1"
            PORT = port.toString()
            USE_SOCKET_TRANSPORT = true
            SERVER_MODE = false // attach to the listening VM, not listen for it
        }
        runManager.addConfiguration(settings)
        return configurationName
    }

    // ── Tool invocation ─────────────────────────────────────────────────────────────────

    /**
     * Runs a tool on a pooled thread while this (EDT) thread pumps the event queue — a direct
     * blocking call would deadlock, because every tool hops onto the EDT internally.
     * Mirrors `McpHttpTestCase.pumpingEdt`.
     */
    private fun runTool(tool: McpTool, arguments: JsonObject, timeoutMs: Long = 90_000): CallToolResult {
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

    /**
     * `step_over` returns as soon as the step is *initiated*; the session may report the previous
     * pause position for a few milliseconds until the step completes. Poll `wait_for_pause` until
     * the reported line moves off the breakpoint line.
     */
    private fun awaitPauseAtLine(expectedLine: Int): JsonObject {
        val deadline = System.currentTimeMillis() + 30_000
        var last: JsonObject? = null
        while (System.currentTimeMillis() < deadline) {
            val status = structured(runTool(WaitForPauseTool(), buildJsonObject { put("timeout", 20) }))
            last = status
            assertEquals("Session died while waiting for the step to land: $status", "paused", status.str("waitResult"))
            val line = status["currentLocation"]?.jsonObject?.get("line")?.jsonPrimitive?.int
            if (line == expectedLine) return status
            Thread.sleep(50)
        }
        throw AssertionError("Step never landed on line $expectedLine; last status: $last")
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
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
        val project = fixture.project
        val manager = XDebuggerManager.getInstance(project)
        if (manager.debugSessions.isEmpty()) return
        manager.debugSessions.forEach { runCatching { it.stop() } }
        waitUntil("leftover debug sessions to stop") {
            XDebuggerManager.getInstance(project).debugSessions.isEmpty()
        }
    }
}
