package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip and contract tests for [BuiltinShortcutStore].
 *
 * JSON storage means any key char survives — including the `:`, `,`, `;` that the
 * old inline delimited format could not (review finding F3). Pure Kotlin + org.json,
 * so this runs as a plain JUnit test.
 */
class BuiltinShortcutStoreTest {

    private fun roundTrip(m: Map<String, String>): Map<String, String> =
        BuiltinShortcutStore.parse(BuiltinShortcutStore.serialize(m))

    @Test
    fun `keys with delimiter chars survive round-trip`() {
        val m = mapOf(
            "tabs" to ":",
            "new_tab" to ",",
            "actions" to ";",
            "settings" to "\"",
            "close" to "q",
        )
        assertEquals(BuiltinShortcutStore.normalize(m), roundTrip(m))
    }

    @Test
    fun `unknown command ids are dropped`() {
        val parsed = BuiltinShortcutStore.parse("{\"tabs\":\"t\",\"bogus\":\"x\"}")
        assertEquals(mapOf("tabs" to "t"), parsed)
    }

    @Test
    fun `empty value is preserved as a hidden command`() {
        val m = mapOf("tabs" to "", "close" to "q")
        assertEquals(m, roundTrip(m))
    }

    @Test
    fun `multi-char value is normalized to its last char`() {
        assertEquals(mapOf("tabs" to "t"), BuiltinShortcutStore.normalize(mapOf("tabs" to "qt")))
    }

    @Test
    fun `uppercase key is normalized to lowercase`() {
        assertEquals(mapOf("tabs" to "t"), BuiltinShortcutStore.normalize(mapOf("tabs" to "T")))
    }

    @Test
    fun `null returns defaults`() {
        assertEquals(BuiltinShortcutStore.defaults, BuiltinShortcutStore.parse(null))
    }

    @Test
    fun `blank returns defaults`() {
        assertEquals(BuiltinShortcutStore.defaults, BuiltinShortcutStore.parse("   "))
    }

    @Test
    fun `malformed json returns defaults`() {
        assertEquals(BuiltinShortcutStore.defaults, BuiltinShortcutStore.parse("not json {["))
    }
}
