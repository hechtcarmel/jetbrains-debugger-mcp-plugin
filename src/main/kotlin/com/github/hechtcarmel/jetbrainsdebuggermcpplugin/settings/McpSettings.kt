package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

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

    data class State(
        var serverPort: Int = 0,
        var maxHistorySize: Int = 1000,
        var autoScroll: Boolean = true,
        var showNotifications: Boolean = true,
        var logLevel: String = "INFO",
        var enableAutoStart: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var serverPort: Int
        get() = myState.serverPort
        set(value) { myState.serverPort = value }

    var maxHistorySize: Int
        get() = myState.maxHistorySize
        set(value) { myState.maxHistorySize = value }

    var autoScroll: Boolean
        get() = myState.autoScroll
        set(value) { myState.autoScroll = value }

    var showNotifications: Boolean
        get() = myState.showNotifications
        set(value) { myState.showNotifications = value }

    var logLevel: String
        get() = myState.logLevel
        set(value) { myState.logLevel = value }

    var enableAutoStart: Boolean
        get() = myState.enableAutoStart
        set(value) { myState.enableAutoStart = value }

    companion object {
        @JvmStatic
        fun getInstance(): McpSettings = service()
    }
}
