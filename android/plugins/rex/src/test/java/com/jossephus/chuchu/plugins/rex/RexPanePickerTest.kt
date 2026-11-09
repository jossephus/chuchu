package com.jossephus.chuchu.plugins.rex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RexPanePickerTest {
    @Test
    fun picksFocusedTerminalOfActiveWindow() {
        val view =
            view(
                active = "window:b",
                window("window:a", focused = "block:a1", blocks = listOf(terminal("block:a1"))),
                window("window:b", focused = "block:b2", blocks = listOf(terminal("block:b1"), terminal("block:b2"))),
            )
        assertEquals("block:b2", RexPanePicker.pick(view))
    }

    @Test
    fun zoomedBlockWinsOverFocus() {
        val view =
            view(
                active = "window:a",
                window("window:a", focused = "block:a1", zoomed = "block:a2", blocks = listOf(terminal("block:a1"), terminal("block:a2"))),
            )
        assertEquals("block:a2", RexPanePicker.pick(view))
    }

    @Test
    fun skipsBlocksThatAreNotTerminals() {
        val view =
            view(
                active = "window:a",
                window("window:a", focused = "block:web", blocks = listOf(block("block:web", "com.example.browser"), terminal("block:t"))),
            )
        assertEquals("block:t", RexPanePicker.pick(view))
    }

    @Test
    fun fallsBackToAnotherWindowWhenActiveHasNoTerminal() {
        val view =
            view(
                active = "window:a",
                window("window:a", focused = "block:web", blocks = listOf(block("block:web", "com.example.browser"))),
                window("window:b", focused = null, blocks = listOf(terminal("block:t"))),
            )
        assertEquals("block:t", RexPanePicker.pick(view))
    }

    @Test
    fun containsMatchesTerminalBlocksInAnyWindowButNotOtherBlocks() {
        val view =
            view(
                active = "window:a",
                window("window:a", focused = "block:a1", blocks = listOf(terminal("block:a1"))),
                window("window:b", focused = "block:web", blocks = listOf(block("block:web", "com.example.browser"), terminal("block:b1"))),
            )
        assertTrue(RexPanePicker.contains(view, "block:b1"))
        assertFalse(RexPanePicker.contains(view, "block:web"))
        assertFalse(RexPanePicker.contains(view, "block:gone"))
    }

    @Test
    fun terminalBlocksListsEveryWindowInOrderWithLabels() {
        val view =
            view(
                active = "window:b",
                window("window:a", focused = "block:a1", blocks = listOf(terminal("block:a1"), block("block:web", "com.example.browser"))),
                window("window:b", focused = "block:b1", blocks = listOf(terminal("block:b1"), terminal("block:b2"))),
            )
        assertEquals(
            listOf("block:a1" to "block:a1-label", "block:b1" to "block:b1-label", "block:b2" to "block:b2-label"),
            RexPanePicker.terminalBlocks(view),
        )
    }

    @Test
    fun emptySessionHasNoPane() {
        assertNull(RexPanePicker.pick(JSONObject("""{"revision":1,"session_id":"session:x","windows":null}""")))
    }

    private fun view(active: String, vararg windows: String) =
        JSONObject("""{"revision":1,"session_id":"session:x","active_window_id":"$active","windows":[${windows.joinToString(",")}]}""")

    private fun window(id: String, focused: String?, zoomed: String? = null, blocks: List<String>): String {
        val focus = focused?.let { "\"$it\"" } ?: "null"
        val zoom = zoomed?.let { ",\"zoomed_block_id\":\"$it\"" }.orEmpty()
        return """{"window_id":"$id","focused_block_id":$focus$zoom,"layers":[{"layer_id":"l","kind":"tiled","bounds":[0,0,1,1],"layout":null,"blocks":[${blocks.joinToString(",")}]}]}"""
    }

    private fun terminal(id: String) = block(id, "com.superlogical.terminal")

    private fun block(id: String, creator: String) = """{"block_id":"$id","label":"$id-label","creator_name":"$creator"}"""
}
