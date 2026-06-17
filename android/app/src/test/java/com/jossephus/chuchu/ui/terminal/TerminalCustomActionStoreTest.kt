package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [TerminalCustomActionStore] serialization.
 *
 * The store persists to a single SharedPreferences string. It uses JSON so that arbitrary payload
 * text — including the delimiter characters the old format choked on (`::`, `|`, `=`, newlines,
 * quotes) and the embedded `[[chu_mods:...]]` marker — round-trips losslessly.
 *
 * Pure Kotlin + org.json, so this runs as a plain JUnit test.
 */
class TerminalCustomActionStoreTest {

    private fun roundTrip(groups: List<TerminalCustomKeyGroup>): List<TerminalCustomKeyGroup> {
        return TerminalCustomActionStore.parse(TerminalCustomActionStore.serialize(groups))
    }

    private fun group(keyLabel: String, vararg actions: TerminalCustomAction) =
        TerminalCustomKeyGroup(keyLabel = keyLabel, actions = actions.toList())

    @Test
    fun `payload containing double colon survives round-trip`() {
        // This is the regression: '::' was the action-field delimiter, so a payload
        // like 'std::cout' was truncated and the tail misread as a shortcut.
        val groups =
            listOf(group("c", TerminalCustomAction(label = "cout", payload = "std::cout << x")))
        assertEquals(TerminalCustomActionStore.normalize(groups), roundTrip(groups))
    }

    @Test
    fun `payload containing other delimiter characters survives round-trip`() {
        val groups =
            listOf(
                group(
                    "x",
                    TerminalCustomAction(label = "pipe", payload = "ls | grep a::b"),
                    TerminalCustomAction(label = "eq", payload = "FOO=bar=baz"),
                    TerminalCustomAction(label = "nl", payload = "line1\nline2"),
                    TerminalCustomAction(label = "quote", payload = "echo \"hi\""),
                )
            )
        assertEquals(TerminalCustomActionStore.normalize(groups), roundTrip(groups))
    }

    @Test
    fun `encoded modifier payload survives round-trip`() {
        val payload =
            encodeCustomActionValue(
                "ls -la",
                setOf(CustomActionModifier.Ctrl, CustomActionModifier.Shift),
            )
        val groups = listOf(group("m", TerminalCustomAction(label = "list", payload = payload)))
        val parsed = roundTrip(groups)
        assertEquals(TerminalCustomActionStore.normalize(groups), parsed)
        // The embedded [[chu_mods:...]] marker still decodes after persistence.
        val decoded = decodeCustomActionValue(parsed.first().actions.first().payload)
        assertEquals("ls -la", decoded.text)
        assertEquals(
            setOf(CustomActionModifier.Ctrl, CustomActionModifier.Shift),
            decoded.modifiers,
        )
    }

    @Test
    fun `actions with and without shortcuts survive round-trip`() {
        val groups =
            listOf(
                group(
                    "g",
                    TerminalCustomAction(label = "withShortcut", payload = ":w", shortcut = "w"),
                    TerminalCustomAction(label = "noShortcut", payload = ":q"),
                )
            )
        assertEquals(TerminalCustomActionStore.normalize(groups), roundTrip(groups))
    }

    @Test
    fun `multiple groups survive round-trip`() {
        val groups =
            listOf(
                group("a", TerminalCustomAction(label = "one", payload = "1")),
                group("b", TerminalCustomAction(label = "two", payload = "2", shortcut = "2")),
            )
        assertEquals(TerminalCustomActionStore.normalize(groups), roundTrip(groups))
    }

    @Test
    fun `empty list serializes to blank and parses back to empty`() {
        assertEquals("", TerminalCustomActionStore.serialize(emptyList()))
        assertEquals(emptyList<TerminalCustomKeyGroup>(), TerminalCustomActionStore.parse(""))
    }

    @Test
    fun `null raw returns defaults`() {
        assertEquals(
            TerminalCustomActionStore.defaultGroups(),
            TerminalCustomActionStore.parse(null),
        )
    }

    @Test
    fun `legacy delimited format migrates to JSON`() {
        // Pre-JSON on-disk format: "keyLabel=label::payload|..." with no shortcut
        // field. A payload containing "::" must survive (legacy split is on the
        // first "::" only).
        val legacy = "c=cout::std::cout << x|q:::q\ng=two::2"
        val parsed = TerminalCustomActionStore.parse(legacy)
        val expected =
            TerminalCustomActionStore.normalize(
                listOf(
                    group(
                        "c",
                        TerminalCustomAction(label = "cout", payload = "std::cout << x"),
                        TerminalCustomAction(label = "q", payload = ":q"),
                    ),
                    group("g", TerminalCustomAction(label = "two", payload = "2")),
                ),
            )
        assertEquals(expected, parsed)
        // Re-saving the migrated data writes JSON and still round-trips.
        val reserialized = TerminalCustomActionStore.serialize(parsed)
        assertTrue(reserialized.startsWith("["))
        assertEquals(parsed, TerminalCustomActionStore.parse(reserialized))
    }

    @Test
    fun `shortcut is normalized to lowercase`() {
        val groups = listOf(group("g", TerminalCustomAction(label = "a", payload = "x", shortcut = "Q")))
        val normalized = TerminalCustomActionStore.normalize(groups)
        assertEquals("q", normalized.first().actions.first().shortcut)
    }

    @Test
    fun `malformed json returns defaults`() {
        assertEquals(
            TerminalCustomActionStore.defaultGroups(),
            TerminalCustomActionStore.parse("not json {["),
        )
    }
}
