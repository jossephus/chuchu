package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChuchuKeyBindingsTest {
    private val calls = mutableListOf<String>()

    private fun build(
        extras: List<ExtraChuchuBinding>,
        customGroups: List<TerminalCustomKeyGroup> = emptyList(),
    ) =
        ChuchuKeyBindings.build(
            builtinShortcuts = mapOf(BuiltinCommand.Tabs.id to "t"),
            builtinCommandHandlers = mapOf(BuiltinCommand.Tabs to { calls += "builtin:tabs" }),
            customGroups = customGroups,
            onDispatchAction = { calls += "custom:${it.label}" },
            onSelectAmongActions = {},
            extraBindings = extras,
        )

    private fun ChuchuKeyBindings.press(key: String) {
        togglePrefix()
        handleText(key)
    }

    @Test
    fun pluginCommandRunsFromChuchuKey() {
        val bindings = build(listOf(ExtraChuchuBinding('h', "herdr") { calls += "plugin:herdr" }))

        bindings.press("H")

        assertEquals(listOf("plugin:herdr"), calls)
        assertTrue(bindings.hints().contains(ChuchuHint("h", "herdr")))
    }

    @Test
    fun builtinAndCustomActionsKeepTheirKeys() {
        val custom = TerminalCustomKeyGroup("g", listOf(TerminalCustomAction("deploy", "make deploy", "d")))
        val bindings =
            build(
                listOf(
                    ExtraChuchuBinding('t', "steals tabs") { calls += "plugin:t" },
                    ExtraChuchuBinding('d', "steals deploy") { calls += "plugin:d" },
                ),
                customGroups = listOf(custom),
            )

        bindings.press("t")
        bindings.press("d")

        assertEquals(listOf("builtin:tabs", "custom:deploy"), calls)
        assertTrue(bindings.hints().none { it.description.startsWith("steals") })
    }

    @Test
    fun firstPluginBindingForAKeyWins() {
        val bindings =
            build(
                listOf(
                    ExtraChuchuBinding('x', "first") { calls += "first" },
                    ExtraChuchuBinding('X', "second") { calls += "second" },
                ),
            )

        bindings.press("x")

        assertEquals(listOf("first"), calls)
    }
}
