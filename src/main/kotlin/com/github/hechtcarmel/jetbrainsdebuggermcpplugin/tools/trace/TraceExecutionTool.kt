package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.trace

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateExpressionSafetyGuard
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.TraceExecutionResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.TraceHit
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.EvaluatorUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StackFrameUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VariablePresentationUtils
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.VirtualFileResolver
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Runs a configuration under the debugger, recording expressions at each probe, and returns
 * the whole transcript from one call.
 *
 * ## Why this exists alongside the interactive tools
 *
 * Answering "what is this variable at each iteration" with set_breakpoint /
 * start_debug_session / wait_for_pause / resume_execution costs a round trip per stop. Six
 * stops is roughly a dozen calls, and the agent has to hold the running story across all of
 * them. The same question here is one call whose answer is the story.
 *
 * The interactive tools remain the right choice when you do not yet know what to look at —
 * this one requires deciding the expressions up front.
 *
 * ## What it does not do
 *
 * It suspends at every probe, so it perturbs timing exactly as a breakpoint does. For race
 * conditions prefer a logpoint (set_breakpoint with log_message and suspend_policy 'none'),
 * which observes without stopping.
 */
class TraceExecutionTool : AbstractMcpTool() {

    override val name = "trace_execution"

    override val description = """
        Runs a run configuration under the debugger, stopping at each probe to record the listed
        expressions, and returns the whole transcript in ONE call.
        Prefer this over set_breakpoint + wait_for_pause + resume_execution loops whenever you
        already know which expressions you want to watch: it costs a single round trip instead of
        one per stop.
        Each probe declares a file, a 1-based line, the expressions to record there, and how many
        hits to record before the trace stops waiting for it.
        Breakpoints created by this tool are always removed afterwards, including on failure.
        Note: probes suspend execution, so this perturbs timing the same way a breakpoint does —
        for race conditions use a logpoint instead (set_breakpoint with log_message and
        suspend_policy 'none').
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.mutable("Trace Execution")

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("status") {
                put("type", "string")
                put("description", "'completed' when every probe met its budget, 'finished' when the process exited first, 'timeout' otherwise")
            }
            putJsonObject("message") { put("type", "string") }
            putJsonObject("hits") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("hit") { put("type", "integer") }
                        putJsonObject("file") { put("type", "string") }
                        putJsonObject("line") { put("type", "integer") }
                        putJsonObject("presentation") { put("type", "string") }
                        putJsonObject("values") { put("type", "object") }
                    }
                }
            }
            putJsonObject("probesNeverHit") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Probes that never fired, so an empty transcript is distinguishable from a mis-placed probe")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("status")); add(JsonPrimitive("message")); add(JsonPrimitive("hits"))
        })
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            put("configuration_name", stringProperty("Name of the run configuration to debug. Call list_run_configurations to discover it."))
            putJsonObject("probes") {
                put("type", "array")
                put("description", "Where to stop and what to record there. At least one is required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("file_path", stringProperty("Absolute path to the file."))
                        put("line", integerProperty("1-based line number.", minimum = 1))
                        putJsonObject("expressions") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", "Expressions to evaluate at this probe, in the target language.")
                        }
                        put("max_hits", integerProperty("How many hits to record for this probe before it stops being waited for. Default 1.", minimum = 1))
                    }
                    put("required", buildJsonArray {
                        add(JsonPrimitive("file_path")); add(JsonPrimitive("line")); add(JsonPrimitive("expressions"))
                    })
                }
            }
            put("timeout", integerProperty("Maximum seconds for the whole trace. Default 120.", minimum = 1))
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("configuration_name")); add(JsonPrimitive("probes"))
        })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val configName = ToolArguments.requireString(arguments, "configuration_name")
        val timeoutSeconds = ToolArguments.optionalInt(arguments, "timeout", default = DEFAULT_TIMEOUT_SECONDS, min = 1)

        val probes = parseProbes(arguments)
            ?: return createErrorResult("At least one probe is required")
        probes.firstOrNull { it.expressions.isEmpty() }?.let {
            return createErrorResult("Probe at ${it.key()} lists no expressions; a probe with nothing to record would only cost a stop")
        }

        val settings = RunManager.getInstance(project).allSettings.find { it.name == configName }
            ?: return createErrorResult("Run configuration not found: $configName")

        // Every probe expression is user-supplied text that will be evaluated in the debuggee,
        // so it goes through the same guard as evaluate_expression and breakpoint conditions.
        // Checked before anything is placed or launched: a rejected expression should cost
        // nothing, not abort a trace halfway through.
        rejectUnsafeExpressions(project, probes)?.let { return it }

        val placed = mutableListOf<XLineBreakpoint<*>>()
        return try {
            for (probe in probes) {
                val breakpoint = placeBreakpoint(project, probe)
                    ?: return createErrorResult("No breakpoint can be placed at ${probe.key()}")
                placed += breakpoint
            }
            runTrace(project, settings.name, settings, probes, timeoutSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Trace failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            // Always: a trace that died mid-run must not leave its breakpoints in the project.
            removeBreakpoints(project, placed)
        }
    }

    /** Returns an error result for the first expression the safety guard rejects, or null. */
    private fun rejectUnsafeExpressions(project: Project, probes: List<Probe>): CallToolResult? {
        val settings = McpSettings.getInstance()
        for (probe in probes) {
            val file = VirtualFileResolver.resolve(probe.filePath)
            val context = EvaluateExpressionSafetyGuard.Context(
                project = project,
                sourcePosition = file?.let {
                    runReadAction { XDebuggerUtil.getInstance().createPosition(it, probe.line - 1) }
                }
            )
            for (expression in probe.expressions) {
                EvaluateExpressionSafetyGuard.validate(
                    expression = expression,
                    mode = settings.evaluateExpressionSafetyMode,
                    context = context,
                    customRules = settings.customEvaluateExpressionBlockRules
                )?.let { violation ->
                    return createErrorResult(
                        "Probe expression '$expression' at ${probe.key()} rejected: ${violation.toUserMessage()}"
                    )
                }
            }
        }
        return null
    }

    private suspend fun runTrace(
        project: Project,
        configName: String,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        probes: List<Probe>,
        timeoutSeconds: Int
    ): CallToolResult {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        val hits = mutableListOf<TraceHit>()
        val counts = mutableMapOf<String, Int>()

        val session = startAndAwaitSession(project, settings, minOf(timeoutSeconds * 1000L, SESSION_START_TIMEOUT_MS))
            ?: return createErrorResult(
                "The IDE did not start a debug session for '$configName'. Check the Run window: a " +
                    "configuration may be unable to run under the debugger as written."
            )

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            when (awaitNextPause(session, remaining)) {
                PauseOutcome.STOPPED -> return finish(project, session, "finished", hits, probes, counts)
                PauseOutcome.TIMEOUT -> return finish(project, session, "timeout", hits, probes, counts)
                PauseOutcome.PAUSED -> Unit
            }

            val frame = session.currentStackFrame
            val position = frame?.sourcePosition
            val probe = position?.let { pos ->
                probes.firstOrNull { it.matches(pos.file.path, pos.line + 1) }
            }

            if (probe != null) {
                counts[probe.key()] = (counts[probe.key()] ?: 0) + 1
                hits += TraceHit(
                    hit = hits.size + 1,
                    file = position.file.path,
                    line = position.line + 1,
                    presentation = frame.let { StackFrameUtils.renderLabel(it) },
                    values = evaluateAll(session, probe.expressions)
                )
                if (exhausted(counts, probes)) {
                    return finish(project, session, "completed", hits, probes, counts)
                }
            }

            withContext(Dispatchers.EDT) { session.resume() }
        }
        return finish(project, session, "timeout", hits, probes, counts)
    }

    /** Evaluates each expression, recording a failure as its value rather than aborting the trace. */
    private suspend fun evaluateAll(session: XDebugSession, expressions: List<String>): Map<String, String> {
        val evaluator = session.currentStackFrame?.evaluator
            ?: return expressions.associateWith { "<no evaluator in this frame>" }

        return expressions.associateWith { expression ->
            when (val outcome = EvaluatorUtils.evaluate(evaluator, expression, EVALUATION_TIMEOUT_MS)) {
                is EvaluatorUtils.EvaluationOutcome.Success ->
                    VariablePresentationUtils
                        .awaitPresentation(outcome.value, PRESENTATION_TIMEOUT_MS, VariablePresentationUtils.defaultPlaceholderTexts())
                        ?.value
                        ?: VariablePresentationUtils.UNAVAILABLE_VALUE_TEXT

                is EvaluatorUtils.EvaluationOutcome.Failure -> "<error: ${outcome.error}>"
                EvaluatorUtils.EvaluationOutcome.Timeout -> "<evaluation timed out>"
            }
        }
    }

    private suspend fun finish(
        project: Project,
        session: XDebugSession,
        status: String,
        hits: List<TraceHit>,
        probes: List<Probe>,
        counts: Map<String, Int>
    ): CallToolResult {
        if (!session.isStopped) {
            withContext(Dispatchers.EDT) { session.stop() }
        }
        val neverHit = probes.map { it.key() }.filter { (counts[it] ?: 0) == 0 }
        val message = when (status) {
            "completed" -> "Recorded ${hits.size} hits; every probe met its budget"
            "finished" -> "The process exited after ${hits.size} hits"
            else -> "Timed out after ${hits.size} hits"
        }
        return createJsonResult(TraceExecutionResult(status, message, hits, neverHit))
    }

    /**
     * Starts the configuration and returns the session it creates.
     *
     * Subscribes before launching: execution is asynchronous, so reading
     * XDebuggerManager.currentSession straight after the launch call returns null, and polling
     * for it can miss a run that starts and finishes between polls.
     */
    private suspend fun startAndAwaitSession(
        project: Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        timeoutMs: Long
    ): XDebugSession? {
        val started = CompletableDeferred<XDebugSession>()
        val connection = project.messageBus.connect()
        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                // Take the session from the process: XDebugSessionImpl.getDebugProcess() throws
                // at this point, because the session has not been handed its process yet.
                started.complete(debugProcess.session)
            }
        })
        return try {
            withContext(Dispatchers.EDT) {
                ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
            }
            withTimeoutOrNull(timeoutMs) { started.await() }
        } finally {
            connection.disconnect()
        }
    }

    private enum class PauseOutcome { PAUSED, STOPPED, TIMEOUT }

    /**
     * Waits for the session's next stop.
     *
     * The listener is installed before the state is checked, and an already-paused session is
     * reported immediately: a process can reach the next probe faster than this coroutine can
     * start waiting, and that pause must not be lost.
     */
    private suspend fun awaitNextPause(session: XDebugSession, timeoutMs: Long): PauseOutcome {
        if (session.isStopped) return PauseOutcome.STOPPED
        val deferred = CompletableDeferred<PauseOutcome>()
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                deferred.complete(PauseOutcome.PAUSED)
            }

            override fun sessionStopped() {
                deferred.complete(PauseOutcome.STOPPED)
            }
        }
        return try {
            withContext(Dispatchers.EDT) {
                session.addSessionListener(listener)
                when {
                    session.isStopped -> deferred.complete(PauseOutcome.STOPPED)
                    session.isPaused -> deferred.complete(PauseOutcome.PAUSED)
                }
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: PauseOutcome.TIMEOUT
        } finally {
            session.removeSessionListener(listener)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun placeBreakpoint(project: Project, probe: Probe): XLineBreakpoint<*>? {
        val file = VirtualFileResolver.resolve(probe.filePath) ?: return null
        val lineIndex = probe.line - 1
        val type = readAction {
            XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull { it.canPutAt(file, lineIndex, project) }
        } ?: return null

        val manager = getDebuggerManager(project).breakpointManager
        return onEdt {
            com.intellij.openapi.application.WriteAction.compute<XLineBreakpoint<*>, RuntimeException> {
                val typed = type as XLineBreakpointType<XBreakpointProperties<Any>>
                manager.addLineBreakpoint(typed, file.url, lineIndex, typed.createBreakpointProperties(file, lineIndex))
                    .also { it.suspendPolicy = SuspendPolicy.ALL }
            }
        }
    }

    private suspend fun removeBreakpoints(project: Project, breakpoints: List<XLineBreakpoint<*>>) {
        if (breakpoints.isEmpty()) return
        val manager = getDebuggerManager(project).breakpointManager
        onEdt {
            com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                breakpoints.forEach { manager.removeBreakpoint(it) }
            }
        }
    }

    private fun parseProbes(arguments: JsonObject): List<Probe>? {
        val array = arguments["probes"]?.jsonArray ?: return null
        if (array.isEmpty()) return null
        return array.map { element ->
            val obj = element.jsonObject
            Probe(
                filePath = obj["file_path"]?.jsonPrimitive?.content
                    ?: throw com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError("Probe is missing file_path"),
                line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError("Probe is missing a numeric line"),
                expressions = obj["expressions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                maxHits = obj["max_hits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            )
        }
    }

    internal data class Probe(
        val filePath: String,
        val line: Int,
        val expressions: List<String>,
        val maxHits: Int = 1
    ) {
        fun key(): String = "$filePath:$line"

        /** Paths are compared by suffix so a probe can be given relative to the project. */
        fun matches(path: String, oneBasedLine: Int): Boolean =
            oneBasedLine == line && (path == filePath || path.endsWith(filePath))
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val SESSION_START_TIMEOUT_MS = 30_000L
        private const val EVALUATION_TIMEOUT_MS = 5_000L
        private const val PRESENTATION_TIMEOUT_MS = 3_000L

        /**
         * A trace ends when every probe has recorded its budget. Overshoot counts: a probe in a
         * hot loop can be hit again between the check and the resume.
         */
        internal fun exhausted(counts: Map<String, Int>, probes: List<Probe>): Boolean =
            probes.all { (counts[it.key()] ?: 0) >= it.maxHits }
    }
}
