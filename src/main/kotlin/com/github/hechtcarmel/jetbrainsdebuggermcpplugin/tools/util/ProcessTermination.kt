package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.execution.process.ProcessHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Only process lifecycle operations run here; select IDE sessions/metadata on the EDT first. */
internal object ProcessTermination {
    suspend fun request(handler: ProcessHandler, detach: Boolean = false) {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            if (handler.isProcessTerminated || handler.isProcessTerminating) return@withContext
            // ProcessHandler otherwise queues destruction until startNotify(), whose caller
            // could be the EDT. Do not accidentally schedule future EDT-bound cancellation.
            if (!handler.isStartNotified) {
                throw ToolExecutionError("Process has not started yet; retry stopping after startup")
            }
            if (detach) handler.detachProcess() else handler.destroyProcess()
        }
    }
}
