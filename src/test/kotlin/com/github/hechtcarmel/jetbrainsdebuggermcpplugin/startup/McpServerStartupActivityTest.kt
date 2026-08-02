package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "Server started" balloon policy: at most once per application session, not once per
 * project open. The startup activity runs for every opened project, so without the guard every
 * project open re-announced a server that had been running all along.
 */
class McpServerStartupActivityTest {

    @Test
    fun `server started balloon is offered at most once per application session`() {
        McpServerStartupActivity.resetServerStartedBalloonForTests()

        assertTrue(
            "first project open in a session shows the balloon",
            McpServerStartupActivity.shouldShowServerStartedBalloon()
        )
        assertFalse(
            "subsequent project opens in the same session stay silent",
            McpServerStartupActivity.shouldShowServerStartedBalloon()
        )
        assertFalse(McpServerStartupActivity.shouldShowServerStartedBalloon())
    }
}
