package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class HerdrPaneStreamPolicyTest {
    @Test
    fun returnsEmptyOutsideForegroundNativeModeOrWithoutSnapshot() {
        val snapshot = snapshot(panes = listOf("p1"))

        assertEquals(emptySet<String>(), desiredHerdrPaneStreams(null, true, true))
        assertEquals(emptySet<String>(), desiredHerdrPaneStreams(HerdrSnapshot(), true, true))
        assertEquals(emptySet<String>(), desiredHerdrPaneStreams(snapshot, false, true))
        assertEquals(emptySet<String>(), desiredHerdrPaneStreams(snapshot, true, false))
    }

    @Test
    fun returnsFocusedTabLayoutPanes() {
        assertEquals(
            linkedSetOf("p1", "p2"),
            desiredHerdrPaneStreams(snapshot(panes = listOf("p1", "p2")), true, true),
        )
    }

    @Test
    fun returnsOnlyFocusedPaneForZoomedLayout() {
        assertEquals(
            setOf("p2"),
            desiredHerdrPaneStreams(snapshot(panes = listOf("p1", "p2"), zoomed = true), true, true),
        )
    }

    @Test
    fun capsPaneStreamsWhileRetainingFocusedPane() {
        val panes = (1..8).map { "p$it" }

        val desired = desiredHerdrPaneStreams(snapshot(panes, focusedPaneId = "p8"), true, true)

        assertEquals(6, desired.size)
        assertEquals(true, "p8" in desired)
        assertEquals(linkedSetOf("p8", "p1", "p2", "p3", "p4", "p5"), desired)
    }

    private fun snapshot(
        panes: List<String>,
        focusedPaneId: String = "p2",
        zoomed: Boolean = false,
    ): HerdrSnapshot =
        HerdrSnapshot(
            focusedTabId = "tab",
            focusedPaneId = focusedPaneId,
            layouts = listOf(
                HerdrTabLayout(
                    tabId = "tab",
                    focusedPaneId = focusedPaneId,
                    panes = panes.map { HerdrLayoutPane(paneId = it) },
                    zoomed = zoomed,
                ),
            ),
        )
}
