package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal interface AndroidProcessHandle

internal data class AndroidProcessCandidate(val info: AndroidProcessInfo, val handle: AndroidProcessHandle)

internal interface AndroidAttachBackend {
    suspend fun processes(project: Project): List<AndroidProcessCandidate>
    suspend fun attach(project: Project, process: AndroidProcessCandidate, debuggerId: String): XDebugSession

    companion object {
        val EP_NAME = ExtensionPointName.create<AndroidAttachBackend>(
            "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.androidAttachBackend"
        )
    }
}

/** Resolve once per invocation: optional extensions may change when Android is disabled or updated. */
internal suspend fun <T> withAndroidBackend(
    injected: AndroidAttachBackend?, action: suspend (AndroidAttachBackend) -> T
): T = try {
    val backend = injected ?: AndroidAttachBackend.EP_NAME.extensionList.singleOrNull()
        ?: throw ToolExecutionError(
            "Android process attachment requires the Android plugin with a supported attach API. " +
                "Check the Android plugin installation or use the IDE's Attach Debugger to Android Process action."
        )
    action(backend)
} catch (e: LinkageError) {
    throw ToolExecutionError(
        "Android plugin API is unavailable or incompatible (${e.javaClass.simpleName}: ${e.message}). " +
            "Check the Android plugin installation or use the IDE's Attach Debugger to Android Process action."
    )
}

internal suspend fun <T> awaitBridge(future: Future<T>): T {
    // Carry failures as values so coroutine stack-trace recovery does not rewrap ExecutionException.
    // The IDE owns this shared initialization future; a timed-out caller must not cancel it.
    val initialization = runInterruptible(Dispatchers.IO) {
        try {
            Result.success(future.get(10, TimeUnit.SECONDS))
        } catch (e: ExecutionException) {
            Result.failure<T>(e.cause ?: e)
        } catch (e: TimeoutException) {
            Result.failure<T>(ToolExecutionError("Android debug bridge initialization timed out. Check the SDK/ADB configuration and retry."))
        }
    }
    return initialization.getOrThrow()
}
