package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.ToggleField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsTest {
    private val prefs = InMemoryPluginPrefs()
    private val global = PluginSettingValues.global(prefs)

    private val native = ToggleField("native_splits", "native splits", default = true)

    @Test
    fun unsetFieldsReadTheirDefaults() {
        assertTrue(global[native])
    }

    @Test
    fun valuesWrittenByTheUiAreReadBackTyped() {
        global.set(native, "false")

        assertFalse(global[native])
        assertEquals("false", global.raw(native))
    }

    @Test
    fun hostValuesAreIsolatedPerHostAndFromGlobal() {
        val hostA = PluginSettingValues.hostValues(prefs, 1)
        val hostB = PluginSettingValues.hostValues(prefs, 2)

        hostA.set(native, "false")

        assertFalse(global.forHost(1)[native])
        assertTrue(hostB[native])
        assertTrue(global[native])
    }

    @Test
    fun removeHostDropsOnlyThatHostsValues() {
        PluginSettingValues.hostValues(prefs, 1).set(native, "false")
        PluginSettingValues.hostValues(prefs, 12).set(native, "false")

        PluginSettingValues.removeHost(prefs, 1)

        assertNull(prefs.get("h.1.native_splits"))
        assertEquals("false", prefs.get("h.12.native_splits"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaRejectsDuplicateKeys() {
        SettingsSchema(listOf(native, ToggleField("native_splits", "again")))
    }
}
