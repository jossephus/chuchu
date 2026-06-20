package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jossephus.chuchu.ui.theme.ChuSymbolsFontFamily

/**
 * Focused tests for pure accessory-bar logic:
 * sticky modifier persistence, catalog entries, and dispatch behavior.
 *
 * These test only Kotlin/non-Android APIs so they run as plain JUnit.
 */
class AccessoryLogicTest {

    // ── ModifierState ──────────────────────────────────────────────────────

    @Test
    fun `toggle each modifier independently`() {
        val s0 = ModifierState()
        assertFalse(s0.hasActiveModifiers())

        val s1 = s0.toggle(TerminalModifier.Ctrl)
        assertTrue(s1.ctrl)
        assertFalse(s1.alt)
        assertFalse(s1.shift)
        assertFalse(s1.cmd)
        assertTrue(s1.hasActiveModifiers())

        val s2 = s1.toggle(TerminalModifier.Ctrl)
        assertFalse(s2.ctrl)
        assertFalse(s2.hasActiveModifiers())

        val s3 = s2.toggle(TerminalModifier.Alt).toggle(TerminalModifier.Shift)
        assertTrue(s3.alt)
        assertTrue(s3.shift)
        assertFalse(s3.ctrl)
        assertFalse(s3.cmd)
    }

    @Test
    fun `isEnabled returns correct values`() {
        val s = ModifierState(ctrl = true, alt = false, shift = true, cmd = false)
        assertTrue(s.isEnabled(TerminalModifier.Ctrl))
        assertFalse(s.isEnabled(TerminalModifier.Alt))
        assertTrue(s.isEnabled(TerminalModifier.Shift))
        assertFalse(s.isEnabled(TerminalModifier.Cmd))
    }

    @Test
    fun `terminalMods encodes bits correctly`() {
        assertEquals(0, ModifierState().terminalMods())
        assertEquals(1, ModifierState(shift = true).terminalMods())   // bit 0
        assertEquals(2, ModifierState(ctrl = true).terminalMods())    // bit 1
        assertEquals(4, ModifierState(alt = true).terminalMods())    // bit 2
        assertEquals(8, ModifierState(cmd = true).terminalMods())    // bit 3
        assertEquals(15, ModifierState(ctrl = true, alt = true, shift = true, cmd = true).terminalMods())
    }

    @Test
    fun `applyToText returns original for empty or no modifiers`() {
        assertEquals("", ModifierState().applyToText(""))
        assertEquals("hello", ModifierState().applyToText("hello"))
    }

    @Test
    fun `applyToText ctrl-modifies first char lower case`() {
        // ctrl+a -> SOH (0x01)
        val result = ModifierState(ctrl = true).applyToText("ab")
        assertEquals(0x01.toChar().toString() + "b", result)
    }

    @Test
    fun `applyToText ctrl-modifies first char upper case`() {
        // ctrl+A -> SOH (0x01)
        val result = ModifierState(ctrl = true).applyToText("Ab")
        assertEquals(0x01.toChar().toString() + "b", result)
    }

    @Test
    fun `applyToText ctrl C is ETX`() {
        assertEquals(0x03.toChar().toString(), ModifierState(ctrl = true).applyToText("c"))
    }

    @Test
    fun `applyToText alt wraps in escape prefix`() {
        val result = ModifierState(alt = true).applyToText("xy")
        assertEquals("\u001bxy", result)
    }

    @Test
    fun `applyToText ctrl+alt combines both`() {
        val result = ModifierState(ctrl = true, alt = true).applyToText("a")
        assertEquals("\u001b${0x01.toChar()}", result)
    }

    // ── TerminalAccessoryDispatcher ───────────────────────────────────────

    @Test
    fun `dispatch ToggleModifier toggles and preserves other modifiers`() {
        val s0 = ModifierState(ctrl = true)
        val r = TerminalAccessoryDispatcher.dispatch(
            AccessoryAction.ToggleModifier(TerminalModifier.Alt),
            s0,
        )
        assertTrue(r.modifierState.ctrl)   // preserved
        assertTrue(r.modifierState.alt)    // toggled on
        assertNull(r.specialKey)
        assertNull(r.text)
        assertFalse(r.shouldPaste)
        assertFalse(r.suppressImeInput)
    }

    @Test
    fun `dispatch SendSpecialKey preserves modifiers and sets suppressImeInput`() {
        val s = ModifierState(shift = true)
        val r = TerminalAccessoryDispatcher.dispatch(
            AccessoryAction.SendSpecialKey(TerminalSpecialKey.Tab),
            s,
        )
        assertTrue(r.modifierState.shift)   // sticky — preserved
        assertEquals(TerminalSpecialKey.Tab, r.specialKey)
        assertTrue(r.suppressImeInput)
        assertNull(r.text)
        assertFalse(r.shouldPaste)
    }

    @Test
    fun `dispatch SendText applies modifier to text`() {
        val s = ModifierState(ctrl = true)
        val r = TerminalAccessoryDispatcher.dispatch(
            AccessoryAction.SendText("a"),
            s,
        )
        assertTrue(r.modifierState.ctrl)   // sticky — preserved
        assertEquals(0x01.toChar().toString(), r.text)
        assertNull(r.specialKey)
        assertFalse(r.shouldPaste)
    }

    @Test
    fun `dispatch Paste returns shouldPaste and preserves modifiers`() {
        val s = ModifierState(alt = true)
        val r = TerminalAccessoryDispatcher.dispatch(
            AccessoryAction.Paste,
            s,
        )
        assertTrue(r.modifierState.alt)   // sticky — preserved
        assertTrue(r.shouldPaste)
        assertNull(r.specialKey)
        assertNull(r.text)
        assertFalse(r.suppressImeInput)
    }

    // ── Catalog entries ───────────────────────────────────────────────────

    @Test
    fun `catalog contains backspace`() {
        val ids = TerminalAccessoryLayoutStore.catalog().map { it.id }
        assertTrue("catalog must contain backspace", "backspace" in ids)
    }

    @Test
    fun `catalog contains minus`() {
        val ids = TerminalAccessoryLayoutStore.catalog().map { it.id }
        assertTrue("catalog must contain minus", "minus" in ids)
    }

    @Test
    fun `catalog contains slash`() {
        val ids = TerminalAccessoryLayoutStore.catalog().map { it.id }
        assertTrue("catalog must contain slash", "slash" in ids)
    }

    @Test
    fun `backspace key has repeatable flag`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "backspace" }
        val key = (item.action as? AccessoryAction.SendSpecialKey)?.key
        assertNotNull(key)
        assertTrue("Backspace must be repeatable", key!!.isRepeatable)
    }

    @Test
    fun `arrow keys have repeatable flag`() {
        for (id in listOf("up", "down", "left", "right")) {
            val item = TerminalAccessoryLayoutStore.catalog().first { it.id == id }
            val key = (item.action as? AccessoryAction.SendSpecialKey)?.key
            assertNotNull("$id must be a special key", key)
            assertTrue("$id must be repeatable", key!!.isRepeatable)
        }
    }

    @Test
    fun `function keys are not repeatable`() {
        for (id in (1..12).map { "f$it" }) {
            val item = TerminalAccessoryLayoutStore.catalog().first { it.id == id }
            val key = (item.action as? AccessoryAction.SendSpecialKey)?.key
            assertNotNull("$id must be a special key", key)
            assertFalse("$id must not be repeatable", key!!.isRepeatable)
        }
    }

    @Test
    fun `tab and enter are not repeatable`() {
        for (id in listOf("tab", "enter")) {
            val item = TerminalAccessoryLayoutStore.catalog().first { it.id == id }
            val key = (item.action as? AccessoryAction.SendSpecialKey)?.key
            assertNotNull("$id must be a special key", key)
            assertFalse("$id must not be repeatable", key!!.isRepeatable)
        }
    }

    // ── Layout store ──────────────────────────────────────────────────────

    @Test
    fun `defaultLayout includes expected keys`() {
        val layout = TerminalAccessoryLayoutStore.defaultLayout()
        val ids = layout.map { it.id }
        assertTrue("escape" in ids)
        assertTrue("up" in ids)
        assertTrue("down" in ids)
        assertTrue("ctrl" in ids)
        assertTrue("cmd" in ids)
    }

    @Test
    fun `normalizeIds removes unknown ids and deduplicates`() {
        val result = TerminalAccessoryLayoutStore.normalizeIds(
            listOf("escape", "unknown", "tab", "escape", "nope"),
        )
        assertEquals(listOf("escape", "tab"), result)
    }

    @Test
    fun `resolveSelectedLayout returns items for valid ids`() {
        val items = TerminalAccessoryLayoutStore.resolveSelectedLayout(
            listOf("escape", "ctrl", "up"),
        )
        assertEquals(3, items.size)
        assertEquals("escape", items[0].id)
        assertEquals("ctrl", items[1].id)
        assertEquals("up", items[2].id)
    }

    @Test
    fun `resolveSelectedLayout with empty list returns empty`() {
        val items = TerminalAccessoryLayoutStore.resolveSelectedLayout(emptyList())
        assertTrue(items.isEmpty())
    }

    // ── Paste action convenience ──────────────────────────────────────────

    @Test
    fun `catalog paste item is Paste action`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "paste" }
        assertTrue(item.action is AccessoryAction.Paste)
    }

    // ── UI action dispatch ───────────────────────────────────────────────

    @Test
    fun `dispatch Settings returns empty result`() {
        val s = ModifierState(ctrl = true)
        val r = TerminalAccessoryDispatcher.dispatch(AccessoryAction.Settings, s)
        assertTrue(r.modifierState.ctrl)
        assertNull(r.specialKey)
        assertNull(r.text)
        assertFalse(r.shouldPaste)
        assertFalse(r.suppressImeInput)
    }

    @Test
    fun `dispatch ChuchuKey returns empty result`() {
        val s = ModifierState()
        val r = TerminalAccessoryDispatcher.dispatch(AccessoryAction.ChuchuKey, s)
        assertFalse(r.modifierState.hasActiveModifiers())
        assertNull(r.specialKey)
        assertNull(r.text)
        assertFalse(r.shouldPaste)
        assertFalse(r.suppressImeInput)
    }

    @Test
    fun `dispatch OpenFiles returns empty result`() {
        val s = ModifierState()
        val r = TerminalAccessoryDispatcher.dispatch(AccessoryAction.OpenFiles, s)
        assertFalse(r.modifierState.hasActiveModifiers())
        assertNull(r.specialKey)
        assertNull(r.text)
        assertFalse(r.shouldPaste)
        assertFalse(r.suppressImeInput)
    }

    // ── New catalog entries ──────────────────────────────────────────────

    @Test
    fun `catalog contains chuchu_key`() {
        val ids = TerminalAccessoryLayoutStore.catalog().map { it.id }
        assertTrue("catalog must contain chuchu_key", "chuchu_key" in ids)
    }

    @Test
    fun `catalog contains open_files`() {
        val ids = TerminalAccessoryLayoutStore.catalog().map { it.id }
        assertTrue("catalog must contain open_files", "open_files" in ids)
    }

    @Test
    fun `catalog contains settings`() {
        val ids = TerminalAccessoryLayoutStore.catalog().map { it.id }
        assertTrue("catalog must contain settings", "settings" in ids)
    }

    @Test
    fun `chuchu_key is ChuchuKey action with Medium width`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "chuchu_key" }
        assertTrue(item.action is AccessoryAction.ChuchuKey)
        assertEquals(KeyWidth.Medium, item.width)
    }

    @Test
    fun `open_files is OpenFiles action with Medium width and symbol font`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "open_files" }
        assertTrue(item.action is AccessoryAction.OpenFiles)
        assertEquals(KeyWidth.Medium, item.width)
        assertEquals(ChuSymbolsFontFamily, item.labelFontFamily)
    }

    @Test
    fun `settings is Settings action with Medium width`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "settings" }
        assertTrue(item.action is AccessoryAction.Settings)
        assertEquals(KeyWidth.Medium, item.width)
    }

    // ── Width categories ─────────────────────────────────────────────────

    @Test
    fun `arrow keys have Medium width`() {
        for (id in listOf("up", "down", "left", "right")) {
            val item = TerminalAccessoryLayoutStore.catalog().first { it.id == id }
            assertEquals("$id must have Medium width", KeyWidth.Medium, item.width)
        }
    }

    @Test
    fun `enter has Medium width`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "enter" }
        assertEquals(KeyWidth.Medium, item.width)
    }

    @Test
    fun `space has Medium width`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "space" }
        assertEquals(KeyWidth.Medium, item.width)
    }

    @Test
    fun `shift has Medium width`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "shift" }
        assertEquals(KeyWidth.Medium, item.width)
    }

    @Test
    fun `tab has Medium width (default)`() {
        val item = TerminalAccessoryLayoutStore.catalog().first { it.id == "tab" }
        assertEquals(KeyWidth.Medium, item.width)
    }

    // ── Default layout includes new items ────────────────────────────────

    @Test
    fun `defaultLayout includes chuchu_key open_files and settings`() {
        val ids = TerminalAccessoryLayoutStore.defaultLayout().map { it.id }
        assertTrue("chuchu_key" in ids)
        assertTrue("open_files" in ids)
        assertTrue("settings" in ids)
    }
}
