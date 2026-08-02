package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket

/**
 * Pins the service's start-up contract: constructing [McpServerService] must never bind a
 * socket (the tool window resolves the service on the EDT), and [McpServerService.ensureStarted]
 * is the single, once-per-session trigger driven by the startup activity.
 *
 * The service is constructed directly rather than through the service container so the test
 * observes the constructor's behaviour in isolation — the app-level instance may already have
 * been started by the real startup activity running against the test project.
 */
class McpServerServiceLifecycleTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            scope.cancel("test finished")
        } finally {
            super.tearDown()
        }
    }

    fun `test constructing the service does not start the server`() {
        val service = McpServerService(scope)
        try {
            assertFalse("constructor must not bind a socket", service.isServerRunning())
            assertNull("no URL before ensureStarted", service.getServerUrl())
            assertNull("no error before ensureStarted", service.getServerError())
        } finally {
            service.dispose()
        }
    }

    fun `test ensureStarted starts the server exactly once per session`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val settings = McpSettings.getInstance()
        val originalPort = settings.state.serverPort
        val service = McpServerService(scope)
        try {
            settings.serverPort = freePort

            service.ensureStarted()
            assertTrue("first ensureStarted must start the server", service.isServerRunning())
            val url = service.getServerUrl()
            assertNotNull(url)
            assertTrue("URL must use the configured port", url!!.contains(":$freePort"))

            service.ensureStarted()
            assertTrue("repeated ensureStarted keeps the server running", service.isServerRunning())
            assertEquals("repeated ensureStarted must not rebind elsewhere", url, service.getServerUrl())

            // The guard is once-per-session: an explicit stop is not silently undone by a
            // later ensureStarted (e.g. another project opening).
            service.stopServer()
            service.ensureStarted()
            assertFalse(
                "ensureStarted after an explicit stop must not restart the server",
                service.isServerRunning()
            )
        } finally {
            service.dispose()
            settings.state.serverPort = originalPort
        }
    }
}
