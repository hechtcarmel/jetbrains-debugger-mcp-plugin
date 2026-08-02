package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpSettingsStateTest {

    /**
     * Host and port are machine-specific. If the storage roamed via Settings Sync, one
     * machine's port choice (made to dodge a local conflict) would propagate to every other
     * machine and recreate the conflict there.
     */
    @Test
    fun `settings storage is excluded from roaming`() {
        val state = McpSettings::class.java.getAnnotation(State::class.java)
        assertNotNull("McpSettings must carry @State", state)

        val storages = state!!.storages
        assertEquals(1, storages.size)
        assertEquals(RoamingType.DISABLED, storages[0].roamingType)
    }
}
