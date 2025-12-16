package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings",
    storages = [Storage("JetBrainsDebuggerMcpPlugin.xml")]
)
class McpSettings : PersistentStateComponent<McpSettings.State> {

    /**
     * Persistent state for MCP settings.
     * Note: serverPort defaults to -1 (unset), which means "use IDE-specific default".
     * This allows different IDEs to have different default ports.
     */
    data class State(
        var maxHistorySize: Int = 1000,
        var serverPort: Int = -1, // -1 means use IDE-specific default
        var migratedToVersion: Int = 0 // Track migration status (2 = v2.0.0 migration done)
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var maxHistorySize: Int
        get() = myState.maxHistorySize
        set(value) { myState.maxHistorySize = value }

    var serverPort: Int
        get() = if (myState.serverPort == -1) McpConstants.getDefaultServerPort() else myState.serverPort
        set(value) { myState.serverPort = value }

    /**
     * Checks if migration to v2.0.0 is needed (user upgrading from v1.x).
     * Returns true if user had the plugin installed before v2.0.0.
     */
    fun needsV2Migration(): Boolean {
        // If already migrated to v2, no need
        if (myState.migratedToVersion >= 2) return false

        // If this is a fresh install (all defaults), no migration needed
        // A fresh install would have: serverPort=-1, maxHistorySize=1000
        val isFreshInstall = myState.serverPort == -1 && myState.maxHistorySize == 1000

        return !isFreshInstall
    }

    /**
     * Marks the v2.0.0 migration as complete.
     */
    fun markV2MigrationComplete() {
        myState.migratedToVersion = 2
    }

    companion object {
        @JvmStatic
        fun getInstance(): McpSettings = service()
    }
}
