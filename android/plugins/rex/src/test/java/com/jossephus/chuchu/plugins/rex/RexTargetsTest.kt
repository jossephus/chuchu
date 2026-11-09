package com.jossephus.chuchu.plugins.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RexTargetsTest {
    @Test
    fun paneTargetRoundTripsIdsContainingSeparators() {
        val request = RexOpenRequest.Block(RexAttachment("session:a b", "block:a1"))
        assertEquals(request, RexTargets.decode(RexTargets.encode(request)))
    }

    @Test
    fun windowAndSessionTargetsRoundTrip() {
        val window = RexOpenRequest.NewWindow("session:1")
        assertEquals(window, RexTargets.decode(RexTargets.encode(window)))
        assertEquals(RexOpenRequest.NewSession, RexTargets.decode(RexTargets.encode(RexOpenRequest.NewSession)))
    }

    @Test
    fun missingOrUnknownTargetFallsBackToFocusedPane() {
        assertNull(RexTargets.encode(RexOpenRequest.Focused))
        assertEquals(RexOpenRequest.Focused, RexTargets.decode(null))
        assertEquals(RexOpenRequest.Focused, RexTargets.decode("nonsense"))
    }

    @Test
    fun duplicateOpensANewWindowInTheAttachedSession() {
        val provider = RexTransportProvider()
        val attached = RexTargets.encode(RexOpenRequest.Block(RexAttachment("session:1", "block:2")))
        assertEquals(RexOpenRequest.NewWindow("session:1"), RexTargets.decode(provider.duplicateTarget(attached)))
        assertEquals(RexOpenRequest.NewSession, RexTargets.decode(provider.duplicateTarget(null)))
    }
}
