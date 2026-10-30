package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.settings

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JTable

class ExposedToolsConfigurableTest : BasePlatformTestCase() {
    fun `test apply reset and cancel preserve unknown tool selections`() {
        val settings = McpSettings.getInstance()
        val original = settings.disabledTools
        val configurable = McpSettingsConfigurable()
        try {
            val service = McpServerService.getInstance()
            val seeded = setOf("future_tool")
            service.updateExposedTools(original, seeded)
            settings.disabledTools = seeded
            val root = configurable.createComponent()
            configurable.reset()
            val table = tables(root).single { table ->
                (0 until table.rowCount).any { table.getValueAt(it, 1) == "jump_to_line" }
            }
            val row = (0 until table.rowCount).single { table.getValueAt(it, 1) == "jump_to_line" }
            assertFalse(configurable.isModified())
            table.setValueAt(false, row, 0)
            assertTrue(configurable.isModified())
            assertEquals(seeded, settings.disabledTools) // Editing alone does not apply.
            configurable.reset()
            assertEquals(true, table.getValueAt(row, 0))
            assertFalse(configurable.isModified())
            table.setValueAt(false, row, 0)
            configurable.apply()
            assertEquals(setOf("future_tool", "jump_to_line"), settings.disabledTools)
            assertFalse(configurable.isModified())
            table.setValueAt(true, row, 0)
            configurable.reset() // Cancel discards the un-applied edit.
            assertEquals(false, table.getValueAt(row, 0))
            assertEquals(setOf("future_tool", "jump_to_line"), settings.disabledTools)
        } finally {
            val current = settings.disabledTools
            McpServerService.getInstance().updateExposedTools(current, original)
            settings.disabledTools = original
            configurable.disposeUIResources()
        }
    }

    private fun tables(component: Component): List<JTable> =
        (if (component is JTable) listOf(component) else emptyList()) +
            (if (component is Container) component.components.flatMap { tables(it) } else emptyList())
}
