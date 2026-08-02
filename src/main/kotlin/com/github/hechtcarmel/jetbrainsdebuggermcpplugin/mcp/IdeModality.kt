package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.withContext

/**
 * Runs [block] with a modality state that permits IDE work while a modal dialog is open.
 *
 * Without this, a tool call issued while the user happens to have Settings (or any modal dialog)
 * open is queued until they close it — the request simply hangs, with no error and no log line.
 *
 * Applied around tool execution rather than around the whole request pipeline: the SDK is free to
 * change which dispatcher it invokes handlers on (0.13.0 moves them to `Dispatchers.Default`), and
 * a wrapper installed further out would silently stop covering the code that needs it.
 *
 * Falls through untouched when no [com.intellij.openapi.application.Application] exists, which is
 * the case in plain unit tests.
 */
suspend fun <T> runWithIdeModality(block: suspend () -> T): T =
    if (ApplicationManager.getApplication() == null) {
        block()
    } else {
        withContext(ModalityState.any().asContextElement()) { block() }
    }
