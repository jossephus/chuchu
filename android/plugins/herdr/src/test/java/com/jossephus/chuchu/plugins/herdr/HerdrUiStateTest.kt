package com.jossephus.chuchu.plugins.herdr

import com.jossephus.chuchu.plugins.herdr.protocol.HerdrLayoutPane
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrPane
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrSnapshot
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrTabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HerdrUiStateTest {
    private fun snapshot(paneIds: List<String>, herdrFocused: String) =
        HerdrSnapshot(
            panes = paneIds.map { HerdrPane(paneId = it, tabId = "t") },
            layouts =
                listOf(
                    HerdrTabLayout(
                        tabId = "t",
                        focusedPaneId = herdrFocused,
                        panes = paneIds.map { HerdrLayoutPane(paneId = it) },
                    ),
                ),
        )

    @Test
    fun localFocusWinsUntilHerdrConfirmsIt() {
        val tapped = HerdrUiState(focusedPaneId = "right")

        assertEquals("right", tapped.withSnapshot(snapshot(listOf("left", "right"), herdrFocused = "left")).focusedPaneId)
        assertNull(tapped.withSnapshot(snapshot(listOf("left", "right"), herdrFocused = "right")).focusedPaneId)
    }

    @Test
    fun localFocusOnAClosedPaneIsDropped() {
        // The pane was closed; keeping it would leave every remaining pane looking unfocused.
        val stale = HerdrUiState(focusedPaneId = "closed")

        assertNull(stale.withSnapshot(snapshot(listOf("left"), herdrFocused = "left")).focusedPaneId)
    }

    @Test
    fun snapshotClearsTheStreamError() {
        val failing = HerdrUiState(error = "herdr didn't answer")

        assertNull(failing.withSnapshot(snapshot(listOf("a"), herdrFocused = "a")).error)
    }
}
