package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.CustomEvaluateExpressionBlockRule
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.EvaluateExpressionSafetyMode
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings.McpSettings",
    // Host and port are machine-specific; syncing them between machines via Settings Sync
    // would replicate one machine's port conflicts onto every other machine.
    storages = [Storage("JetBrainsDebuggerMcpPlugin.xml", roamingType = RoamingType.DISABLED)]
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
        var serverHost: String = "", // empty means use DEFAULT_SERVER_HOST
        var evaluateExpressionSafetyModeId: String = EvaluateExpressionSafetyMode.DEFAULT.id,
        var customEvaluateExpressionBlockRules: MutableList<CustomEvaluateExpressionBlockRule> = mutableListOf(),
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

    var serverHost: String
        get() = myState.serverHost.ifEmpty { McpConstants.DEFAULT_SERVER_HOST }
        set(value) { myState.serverHost = value }

    var evaluateExpressionSafetyMode: EvaluateExpressionSafetyMode
        get() = EvaluateExpressionSafetyMode.fromId(myState.evaluateExpressionSafetyModeId)
        set(value) { myState.evaluateExpressionSafetyModeId = value.id }

    var customEvaluateExpressionBlockRules: MutableList<CustomEvaluateExpressionBlockRule>
        get() = myState.customEvaluateExpressionBlockRules
            .map { it.copyForPersistence() }
            .toMutableList()
        set(value) {
            myState.customEvaluateExpressionBlockRules = value
                .map { it.copyForPersistence() }
                .toMutableList()
        }

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
