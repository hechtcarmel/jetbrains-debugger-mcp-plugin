package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.runconfig

import com.intellij.execution.ExecutionListener
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentWithExecutorListener
import com.intellij.openapi.project.Project

/** Registered with the project message bus, including runs launched from the IDE UI. */
class RunSessionExecutionListener : ExecutionListener {
    private fun accept(executorId: String, env: ExecutionEnvironment, action: RunSessionService.() -> Unit) {
        if (executorId == DefaultRunExecutor.EXECUTOR_ID && !env.project.isDisposed) {
            RunSessionService.getInstance(env.project).event(action)
        }
    }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) =
        accept(executorId, env) { scheduled(env) }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
        accept(executorId, env) { started(env, handler) }

    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
        accept(executorId, env) { starting(env, handler) }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) =
        accept(executorId, env) { failed(env, null) }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) =
        accept(executorId, env) { failed(env, cause?.message) }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) =
        accept(executorId, env) { terminated(env, handler, exitCode) }
}

/** Discovers pre-existing runs when selected, without guessing an executor from a tab title. */
class RunSessionContentListener(private val project: Project) : RunContentWithExecutorListener {
    override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) {
        if (descriptor == null || executor.id != DefaultRunExecutor.EXECUTOR_ID) return
        if (!project.isDisposed) RunSessionService.getInstance(project).event { observed(descriptor) }
    }
}
