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
        var maxHistorySize: Int = 1000
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var maxHistorySize: Int
        get() = myState.maxHistorySize
        set(value) { myState.maxHistorySize = value }

    companion object {
        @JvmStatic
        fun getInstance(): McpSettings = service()
    }
}
