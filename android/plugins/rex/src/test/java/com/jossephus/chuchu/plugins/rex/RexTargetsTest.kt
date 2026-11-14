package com.jossephus.chuchu.plugins.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class RexTargetsTest {
    @Test
    fun paneTargetRoundTripsIdsContainingSeparators() {
        val pane = RexAttachment("session:a b", "block:a1")
        assertEquals(RexOpenRequest.Block(pane), RexTargets.decode(RexTargets.pane(pane)))
    }

    @Test
    fun windowAndSessionTargetsRoundTrip() {
        assertEquals(RexOpenRequest.NewWindow("session:1"), RexTargets.decode(RexTargets.newWindow("session:1")))
        assertEquals(RexOpenRequest.NewSession, RexTargets.decode(RexTargets.newSession()))
    }

    @Test
    fun missingOrUnknownTargetFallsBackToFocusedPane() {
        assertEquals(RexOpenRequest.Focused, RexTargets.decode(null))
        assertEquals(RexOpenRequest.Focused, RexTargets.decode("nonsense"))
    }

    @Test
    fun duplicateOpensANewWindowInTheAttachedSession() {
        val provider = RexTransportProvider()
        val attached = RexTargets.pane(RexAttachment("session:1", "block:2"))
        assertEquals(RexOpenRequest.NewWindow("session:1"), RexTargets.decode(provider.duplicateTarget(attached)))
        assertEquals(RexOpenRequest.NewSession, RexTargets.decode(provider.duplicateTarget(null)))
    }
}
