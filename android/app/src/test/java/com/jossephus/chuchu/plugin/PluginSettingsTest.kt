package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ChoiceField
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.TextField
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
    private val socket = TextField("socket", "socket path", default = "/tmp/herdr.sock")
    private val layout =
        ChoiceField(
            "layout",
            "layout",
            options = listOf(ChoiceField.Option("tabs", "tabs"), ChoiceField.Option("splits", "splits")),
        )

    @Test
    fun unsetFieldsReadTheirDefaults() {
        assertTrue(global[native])
        assertEquals("/tmp/herdr.sock", global[socket])
        assertEquals("tabs", global[layout])
    }

    @Test
    fun valuesWrittenByTheUiAreReadBackTyped() {
        global.set(native, "false")
        global.set(socket, "/run/herdr")
        global.set(layout, "splits")

        assertFalse(global[native])
        assertEquals("/run/herdr", global[socket])
        assertEquals("splits", global[layout])
        assertEquals("false", global.raw(native))
    }

    @Test
    fun choiceFallsBackToDefaultWhenStoredOptionWasRemoved() {
        prefs.put("s.layout", "grid")
        assertEquals("tabs", global[layout])
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
        PluginSettingValues.hostValues(prefs, 1).set(socket, "a")
        PluginSettingValues.hostValues(prefs, 12).set(socket, "b")

        PluginSettingValues.removeHost(prefs, 1)

        assertNull(prefs.get("h.1.socket"))
        assertEquals("b", prefs.get("h.12.socket"))
    }

    @Test
    fun storageIsNamespacedAwayFromSettings() {
        val storage = PluginStorageImpl(prefs)
        storage.put("socket", "cached")

        assertEquals("/tmp/herdr.sock", global[socket])
        assertEquals("cached", storage.get("socket"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaRejectsDuplicateKeys() {
        SettingsSchema(listOf(native, ToggleField("native_splits", "again")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun choiceRejectsDefaultOutsideOptions() {
        ChoiceField("mode", "mode", options = listOf(ChoiceField.Option("a", "a")), default = "b")
    }
}
