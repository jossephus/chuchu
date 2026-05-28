package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCustomActionStoreTest {
    @Test
    fun parseLegacyActionsAssignsStableIds() {
        val groups = TerminalCustomActionStore.parse("qv=qv:::q")

        val action = groups.single().actions.single()
        assertEquals(legacyActionId("qv", "qv", ":q"), action.id)
        assertEquals("qv", action.label)
        assertEquals(":q", action.payload)
    }

    @Test
    fun legacyIdsIncludeGroupLabel() {
        val groups = TerminalCustomActionStore.parse("a=run::ls\nb=run::ls")

        assertEquals(
            listOf(
                legacyActionId("a", "run", "ls"),
                legacyActionId("b", "run", "ls"),
            ),
            groups.flatMap { it.actions }.map { it.id },
        )
    }

    @Test
    fun serializeRoundTripsStableIds() {
        val firstId = "11111111-1111-1111-1111-111111111111"
        val secondId = "22222222-2222-2222-2222-222222222222"
        val groups = listOf(
            TerminalCustomKeyGroup(
                keyLabel = "vim",
                actions = listOf(
                    TerminalCustomAction(id = firstId, label = "qv", payload = ":q"),
                    TerminalCustomAction(id = secondId, label = "write", payload = ":w::file"),
                ),
            ),
        )

        val serialized = TerminalCustomActionStore.serialize(groups)
        val parsed = TerminalCustomActionStore.parse(serialized)

        assertTrue(serialized.contains("id:$firstId::qv::%3Aq"))
        assertEquals(listOf(firstId, secondId), parsed.single().actions.map { it.id })
        assertEquals(":w::file", parsed.single().actions[1].payload)
    }

    @Test
    fun serializeRoundTripsPayloadsWithPipes() {
        val id = "33333333-3333-3333-3333-333333333333"
        val payload = "ls | grep foo"
        val groups = listOf(
            TerminalCustomKeyGroup(
                keyLabel = "shell",
                actions = listOf(TerminalCustomAction(id = id, label = "pipe", payload = payload)),
            ),
        )

        val serialized = TerminalCustomActionStore.serialize(groups)
        val parsed = TerminalCustomActionStore.parse(serialized)

        assertTrue(serialized.contains("%7C"))
        assertEquals(payload, parsed.single().actions.single().payload)
    }

    @Test
    fun resolvePayloadCanAppendEnterForAutorun() {
        val actionId = "55555555-5555-5555-5555-555555555555"
        val groups = listOf(
            TerminalCustomKeyGroup(
                keyLabel = "shell",
                actions = listOf(TerminalCustomAction(id = actionId, label = "pwd", payload = "pwd")),
            ),
        )

        assertEquals("pwd", resolveCustomActionPayload(actionId, groups))
        assertEquals("pwd\n", resolveCustomActionPayload(actionId, groups, appendEnter = true))
    }

    @Test
    fun resolvePayloadDoesNotDoubleAppendEnter() {
        val actionId = "66666666-6666-6666-6666-666666666666"
        val payload = encodeCustomActionValue("pwd", setOf(CustomActionModifier.Enter))
        val groups = listOf(
            TerminalCustomKeyGroup(
                keyLabel = "shell",
                actions = listOf(TerminalCustomAction(id = actionId, label = "pwd", payload = payload)),
            ),
        )

        assertEquals("pwd\n", resolveCustomActionPayload(actionId, groups, appendEnter = true))
    }

    @Test
    fun uuidShapedLegacyLabelsStayLegacy() {
        val label = "44444444-4444-4444-4444-444444444444"
        val payload = "echo::ok"
        val groups = TerminalCustomActionStore.parse("legacy=$label::$payload")

        val action = groups.single().actions.single()
        assertEquals(label, action.label)
        assertEquals(payload, action.payload)
        assertEquals(legacyActionId("legacy", label, payload), action.id)
    }
}
