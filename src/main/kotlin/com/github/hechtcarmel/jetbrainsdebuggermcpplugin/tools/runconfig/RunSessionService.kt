package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConsoleOutputResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunSessionInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunSessionListResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StopSessionResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ConsoleOutputReader
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** EDT-owned bounded metadata; the IDE alone owns all console history and process lifetimes. */
@Service(Service.Level.PROJECT)
class RunSessionService(private val project: Project) : Disposable {
    internal companion object {
        const val MAX_SESSIONS = 128
        const val MAX_DIAGNOSTIC_CHARS = 2048
        val SESSION_KEY = Key.create<String>("debugger.mcp.run.session")
        fun getInstance(project: Project): RunSessionService = project.getService(RunSessionService::class.java)
    }

    private class Entry(val id: String, val name: String) {
        var state = "starting"
        var exitCode: Int? = null
        var failure: String? = null
        var executionId: Long = 0
        var handler: WeakReference<ProcessHandler>? = null
        var descriptor: WeakReference<RunContentDescriptor>? = null
        var processlessConsole: WeakReference<ConsoleView>? = null
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var trackingLimitReached = false
    @Volatile private var disposed = false

    internal fun event(action: RunSessionService.() -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            if (!disposed && !project.isDisposed) action()
        } else {
            app.invokeLater { if (!disposed && !project.isDisposed) action() }
        }
    }

    /** Reserve an ID before launching, including cases where no environment/runner can be built. */
    internal fun reserve(name: String): String {
        assertEdt()
        refresh()
        if (entries.size >= MAX_SESSIONS) {
            val oldestFinished = entries.values.firstOrNull {
                it.state in setOf("terminated", "failed_start", "unavailable") && it.handler?.get()?.isProcessTerminated != false
            }
                ?: throw ToolExecutionError("Run session tracking limit reached ($MAX_SESSIONS). No configuration was launched.")
            entries.remove(oldestFinished.id)
        }
        val id = "run:${UUID.randomUUID()}"
        entries[id] = Entry(id, name.take(512))
        return id
    }

    internal fun scheduled(env: ExecutionEnvironment): String? {
        assertEdt()
        val existing = env.getUserData(SESSION_KEY)
        if (existing != null && entries.containsKey(existing)) {
            entries.getValue(existing).executionId = env.executionId
            return existing
        }
        val id = try { reserve(env.runProfile.name) } catch (_: ToolExecutionError) {
            trackingLimitReached = true
            return null
        }
        env.putUserData(SESSION_KEY, id)
        entries.getValue(id).executionId = env.executionId
        return id
    }

    internal fun starting(env: ExecutionEnvironment, handler: ProcessHandler) {
        started(env, handler)
        entries.values.firstOrNull { it.handler?.get() === handler }?.apply {
            if (!handler.isProcessTerminated) state = "starting"
        }
    }

    internal fun started(env: ExecutionEnvironment, handler: ProcessHandler) {
        val id = scheduled(env) ?: return
        val entry = entries.getValue(id)
        // A grouped/delegated launch can expose several handlers under one environment.
        // Give each process its own exact ID instead of silently replacing an existing target.
        val previous = entry.handler?.get()
        val target = if (previous != null && previous !== handler) {
            val other = entries.values.firstOrNull { it.handler?.get() === handler }
            other ?: try { entries.getValue(reserve(env.runProfile.name)) } catch (_: ToolExecutionError) {
                trackingLimitReached = true
                return
            }
        } else entry
        target.handler = WeakReference(handler)
        target.state = if (handler.isProcessTerminated) "terminated" else "running"
        target.exitCode = handler.exitCode
        attachDescriptor(target)
    }

    internal fun failed(id: String, reason: String?) {
        assertEdt()
        entries[id]?.apply {
            state = "failed_start"
            failure = (reason ?: "Execution was cancelled or failed before a process started").take(MAX_DIAGNOSTIC_CHARS)
        }
    }

    internal fun failed(env: ExecutionEnvironment, reason: String?) {
        val id = scheduled(env) ?: return
        val entry = entries.getValue(id)
        if (entry.handler?.get() != null && entry.state !in setOf("starting", "failed_start")) {
            // A failed member of a compound launch must not overwrite a live member's state.
            val failureId = try { reserve(env.runProfile.name) } catch (_: ToolExecutionError) {
                trackingLimitReached = true
                return
            }
            failed(failureId, reason)
        } else failed(id, reason)
    }

    internal fun terminated(env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        val startFailed = entries.values.any { it.handler?.get() === handler && it.state == "failed_start" }
        started(env, handler)
        entries.values.firstOrNull { it.handler?.get() === handler }?.apply {
            state = if (startFailed) "failed_start" else "terminated"
            this.exitCode = exitCode
        }
    }

    /** The runner callback binds a descriptor to the launch, without guessing a batch ID. */
    internal fun bindDescriptor(id: String, descriptor: RunContentDescriptor) {
        assertEdt()
        val entry = entries[id] ?: return
        val handler = descriptor.processHandler
        val previous = entry.handler?.get()
        if (previous != null && handler !== previous) return
        // Explicit descriptor reuse invalidates old processless ownership before binding anew.
        entries.values.filter { it !== entry && it.descriptor?.get() === descriptor }.forEach {
            it.descriptor = null
            it.processlessConsole = null
        }
        entry.descriptor = WeakReference(descriptor)
        entry.executionId = descriptor.executionId
        entry.handler = handler?.let(::WeakReference)
        entry.processlessConsole = (descriptor.executionConsole as? ConsoleView)?.let(::WeakReference)
    }

    /** The content event supplies the executor explicitly, including shared dashboard tabs. */
    internal fun observed(descriptor: RunContentDescriptor) {
        assertEdt()
        val handler = descriptor.processHandler ?: return
        val known = entries.values.firstOrNull { it.handler?.get() === handler }
        // showRunContent/contentSelected can precede processStarted. Let the scheduled
        // execution claim its handler, retaining the ID already returned to the caller.
        if (known == null && entries.values.any { it.state == "starting" }) return
        val entry = known ?: run {
            val id = try { reserve(descriptor.displayName ?: "Run") } catch (_: ToolExecutionError) {
                trackingLimitReached = true
                return
            }
            entries.getValue(id).apply {
                this.handler = WeakReference(handler)
                state = if (handler.isProcessTerminated) "terminated" else "running"
                exitCode = handler.exitCode
            }
        }
        entry.descriptor = WeakReference(descriptor)
    }

    fun list(): RunSessionListResult {
        assertEdt()
        refresh()
        return RunSessionListResult(entries.values.map(::info), trackingLimitReached, MAX_SESSIONS)
    }

    fun session(id: String): RunSessionInfo {
        assertEdt()
        refresh()
        return info(requireEntry(id))
    }

    fun read(id: String, lines: Int, offset: Int, maxChars: Int): RunConsoleOutputResult {
        assertEdt()
        refresh()
        val entry = requireEntry(id)
        val console = console(entry)
        if (console == null) return RunConsoleOutputResult(info(entry), consoleError =
            "No retained text console is available for this exact Run session. See state and failureMessage; no other session was selected.")
        return try {
            RunConsoleOutputResult(info(entry), ConsoleOutputReader.read(console, id, lines, offset, maxChars))
        } catch (e: ToolExecutionError) {
            RunConsoleOutputResult(info(entry), consoleError = e.message.take(MAX_DIAGNOSTIC_CHARS))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RunConsoleOutputResult(info(entry), consoleError =
                "Unable to read this Run console: ${e.message}".take(MAX_DIAGNOSTIC_CHARS))
        }
    }

    fun stop(id: String): StopSessionResult {
        assertEdt()
        refresh()
        val entry = requireEntry(id)
        val handler = entry.handler?.get()
        if (handler == null || handler.isProcessTerminated) {
            return StopSessionResult(id, "not_running", "This Run session has no running process; no process was stopped")
        }
        // Never search by configuration name, PID, focused tab or execution-id batch.
        if (!handler.isProcessTerminating) handler.destroyProcess()
        entry.state = "stopping"
        return StopSessionResult(id, "stopping", "Stop requested for this exact Run session")
    }

    private fun requireEntry(id: String): Entry {
        if (!id.startsWith("run:")) throw ToolExecutionError("Expected a Run session ID from list_run_sessions, not a Debug session ID")
        return entries[id] ?: throw ToolExecutionError("Run session not found or metadata evicted: $id")
    }

    private fun attachDescriptor(entry: Entry) {
        val handler = entry.handler?.get()
        if (handler == null) return
        val old = entry.descriptor?.get()
        if (old != null && old.processHandler === handler) return
        entry.descriptor = RunContentManager.getInstanceIfCreated(project)?.allDescriptors
            ?.singleOrNull { it.processHandler === handler }?.let(::WeakReference)
    }

    private fun console(entry: Entry): ConsoleView? {
        val handler = entry.handler?.get()
        val descriptor = entry.descriptor?.get() ?: return null
        // A reused Run tab must never expose the newer execution under the old session ID.
        if (handler == null) {
            if (entry.state != "failed_start" || descriptor.executionId != entry.executionId ||
                descriptor.processHandler != null || entry.processlessConsole?.get() !== descriptor.executionConsole) return null
        } else if (descriptor.processHandler !== handler) return null
        return descriptor.executionConsole as? ConsoleView
    }

    private fun info(entry: Entry) = RunSessionInfo(
        entry.id, entry.name, entry.state, entry.exitCode, entry.failure, console(entry) != null
    )

    private fun refresh() {
        for (entry in entries.values) {
            attachDescriptor(entry)
            val handler = entry.handler?.get()
            if (handler != null) {
                if (handler.isProcessTerminated) {
                    if (entry.state != "failed_start") entry.state = "terminated"
                    entry.exitCode = handler.exitCode
                } else if (handler.isProcessTerminating && entry.state != "failed_start") entry.state = "stopping"
            } else if (entry.state in setOf("running", "stopping")) entry.state = "unavailable"
        }
    }

    private fun assertEdt() = ApplicationManager.getApplication().assertIsDispatchThread()

    override fun dispose() {
        // Project disposal can happen off the EDT; queued events check disposed before access.
        disposed = true
    }
}
