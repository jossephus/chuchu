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

    @Test
    fun includesRecentTabPanesAfterFocusedTabPanes() {
        val snapshot =
            snapshot(
                panes = listOf("p1", "p2"),
                additionalLayouts = listOf(layout("recent", listOf("r1", "r2"))),
            )

        assertEquals(
            linkedSetOf("p1", "p2", "r1", "r2"),
            desiredHerdrPaneStreams(snapshot, true, true, recentTabIds = listOf("recent")),
        )
    }

    @Test
    fun capsWarmStreamsWithoutEvictingFocusedPanes() {
        val focusedPanes = (1..8).map { "p$it" }
        val recentPanes = (1..8).map { "r$it" }
        val snapshot =
            snapshot(
                panes = focusedPanes,
                focusedPaneId = "p8",
                additionalLayouts = listOf(layout("recent", recentPanes)),
            )

        val desired = desiredHerdrPaneStreams(snapshot, true, true, recentTabIds = listOf("recent"))

        assertEquals(12, desired.size)
        assertEquals(
            linkedSetOf("p8", "p1", "p2", "p3", "p4", "p5", "r1", "r2", "r3", "r4", "r5", "r6"),
            desired,
        )
    }

    @Test
    fun skipsFocusedTabWhenItAppearsInRecentTabs() {
        val snapshot =
            snapshot(
                panes = listOf("p1", "p2"),
                additionalLayouts = listOf(layout("recent", listOf("r1"))),
            )

        assertEquals(
            linkedSetOf("p1", "p2", "r1"),
            desiredHerdrPaneStreams(snapshot, true, true, recentTabIds = listOf("tab", "recent")),
        )
    }

    @Test
    fun overrideSelectsOverrideTabPanesAsFocused() {
        val snapshot =
            snapshot(
                panes = listOf("p1", "p2"),
                additionalLayouts = listOf(layout("override", listOf("o1", "o2"))),
            )

        assertEquals(
            linkedSetOf("o1", "o2"),
            desiredHerdrPaneStreams(
                snapshot,
                true,
                true,
                focusedTabIdOverride = "override",
            ),
        )
    }

    @Test
    fun overrideKeepsPreviousFocusedTabWarm() {
        val snapshot =
            snapshot(
                panes = listOf("p1", "p2"),
                additionalLayouts = listOf(layout("override", listOf("o1", "o2"))),
            )

        assertEquals(
            linkedSetOf("o1", "o2", "p1", "p2"),
            desiredHerdrPaneStreams(
                snapshot,
                true,
                true,
                recentTabIds = listOf("override", "tab"),
                focusedTabIdOverride = "override",
            ),
        )
    }

    @Test
    fun matchingOverrideBehavesLikeNoOverride() {
        val snapshot =
            snapshot(
                panes = listOf("p1", "p2"),
                additionalLayouts = listOf(layout("recent", listOf("r1"))),
            )
        val withoutOverride =
            desiredHerdrPaneStreams(
                snapshot,
                true,
                true,
                recentTabIds = listOf("tab", "recent"),
            )

        assertEquals(
            withoutOverride,
            desiredHerdrPaneStreams(
                snapshot,
                true,
                true,
                recentTabIds = listOf("tab", "recent"),
                focusedTabIdOverride = "tab",
            ),
        )
    }

    @Test
    fun returnsEmptyOutsideForegroundWithRecentTabs() {
        val snapshot =
            snapshot(
                panes = listOf("p1"),
                additionalLayouts = listOf(layout("recent", listOf("r1"))),
            )

        assertEquals(
            emptySet<String>(),
            desiredHerdrPaneStreams(snapshot, true, false, recentTabIds = listOf("recent")),
        )
    }

    @Test
    fun zoomedRecentTabContributesOnlyItsFocusedPane() {
        val snapshot =
            snapshot(
                panes = listOf("p1"),
                additionalLayouts =
                    listOf(
                        layout(
                            tabId = "recent",
                            panes = listOf("r1", "r2"),
                            focusedPaneId = "r2",
                            zoomed = true,
                        ),
                    ),
            )

        assertEquals(
            linkedSetOf("p1", "r2"),
            desiredHerdrPaneStreams(snapshot, true, true, recentTabIds = listOf("recent")),
        )
    }

    private fun snapshot(
        panes: List<String>,
        focusedPaneId: String = "p2",
        zoomed: Boolean = false,
        additionalLayouts: List<HerdrTabLayout> = emptyList(),
    ): HerdrSnapshot =
        HerdrSnapshot(
            focusedTabId = "tab",
            focusedPaneId = focusedPaneId,
            layouts =
                listOf(
                    layout(
                        tabId = "tab",
                        panes = panes,
                        focusedPaneId = focusedPaneId,
                        zoomed = zoomed,
                    ),
                ) + additionalLayouts,
        )

    private fun layout(
        tabId: String,
        panes: List<String>,
        focusedPaneId: String = panes.first(),
        zoomed: Boolean = false,
    ): HerdrTabLayout =
        HerdrTabLayout(
            tabId = tabId,
            focusedPaneId = focusedPaneId,
            panes = panes.map { HerdrLayoutPane(paneId = it) },
            zoomed = zoomed,
        )
}
