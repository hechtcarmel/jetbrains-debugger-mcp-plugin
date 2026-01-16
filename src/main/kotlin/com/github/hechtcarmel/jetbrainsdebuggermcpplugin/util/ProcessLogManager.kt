package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

object ProcessLogManager {

    private val LOG = thisLogger<ProcessLogManager>()

    private val outputBuffers = ConcurrentHashMap<Int, StringBuilder>()

    /**
     * Retrieves the full log content for a process.
     * Strategy:
     * 1. Try to find the ConsoleView associated with this process in the UI (Run/Debug tabs).
     * This is the most reliable source as it contains the full history.
     * 2. Fallback to our internal captured buffer if the UI component isn't found.
     */
    fun getLogContent(project: Project, processHandler: ProcessHandler): String {
        // Strategy 1: Read from IntelliJ's Console View (Source of Truth)
        val consoleText = getLogFromConsoleView(project, processHandler)
        if (consoleText.isNotEmpty()) {
            return consoleText
        }

        // Strategy 2: Fallback to manual capture (if listener was attached early enough)
        return getCapturedOutput(processHandler)
    }

    private fun getLogFromConsoleView(project: Project, processHandler: ProcessHandler): String {
        return try {
            val contentManager = RunContentManager.getInstance(project)
            val descriptors = contentManager.allDescriptors

            val descriptor = descriptors.find { it.processHandler == processHandler }

            if (descriptor != null) {
                val console = descriptor.executionConsole
                // ConsoleViewImpl is the standard implementation that holds the document
                if (console is ConsoleViewImpl) {
                    // flushing ensures we get the latest text from the buffer
                    console.flushDeferredText()
                    // FIXED: accessing nullable editor safely
                    return console.editor?.document?.text ?: ""
                }
            }
            ""
        } catch (e: Exception) {
            LOG.debug("Error reading from ConsoleView: ${e.message}")
            ""
        }
    }

    fun attachListener(processHandler: ProcessHandler): Int {
        val processHashCode = processHandler.hashCode()
        if (outputBuffers.putIfAbsent(processHashCode, StringBuilder()) == null) {
            val processAdapter = object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    outputBuffers[processHashCode]?.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    // Buffer retained for log retrieval after process ends
                }
            }
            processHandler.addProcessListener(processAdapter)
        }
        return processHashCode
    }

    private fun getCapturedOutput(processHandler: ProcessHandler): String {
        return outputBuffers[processHandler.hashCode()]?.toString() ?: ""
    }

    fun hasListener(processHashCode: Int): Boolean {
        return outputBuffers.containsKey(processHashCode)
    }
}