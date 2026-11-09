package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun moshFailureRequiresExplicitReconnect() {
        assertEquals(
            ReadLoopExitAction.RequireManualReconnect,
            readLoopExitAction(Transport.Mosh),
        )
    }

    @Test
    fun remoteShellTransportsKeepAutomaticReconnect() {
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.SSH),
        )
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.TailscaleSSH),
        )
    }

    @Test
    fun localShellDisconnectsWithoutRemoteReconnect() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.LocalShell),
        )
    }

    @Test
    fun moshContactIsLostWhenInputStaysUnacknowledged() {
        val tracker = MoshContactTracker()
        assertFalse(tracker.isContactLost(pendingOutbound = 0, nowMs = 0))
        assertFalse(tracker.isContactLost(pendingOutbound = 1, nowMs = 100))
        assertFalse(tracker.isContactLost(pendingOutbound = 2, nowMs = 3_099))

        assertTrue(tracker.isContactLost(pendingOutbound = 2, nowMs = 3_100))
    }

    @Test
    fun moshContactRecoversWhenTheServerAcknowledges() {
        val tracker = MoshContactTracker()
        tracker.isContactLost(pendingOutbound = 0, nowMs = 0)
        tracker.isContactLost(pendingOutbound = 1, nowMs = 100)
        assertTrue(tracker.isContactLost(pendingOutbound = 1, nowMs = 5_000))

        assertFalse(tracker.isContactLost(pendingOutbound = 0, nowMs = 5_001))
    }

    @Test
    fun moshPartialAcknowledgementRestartsTheWindow() {
        val tracker = MoshContactTracker()
        tracker.isContactLost(pendingOutbound = 0, nowMs = 0)
        tracker.isContactLost(pendingOutbound = 3, nowMs = 100)
        assertFalse(tracker.isContactLost(pendingOutbound = 2, nowMs = 3_000))

        assertFalse(tracker.isContactLost(pendingOutbound = 2, nowMs = 5_999))
        assertTrue(tracker.isContactLost(pendingOutbound = 2, nowMs = 6_000))
    }

    @Test
    fun moshAcknowledgedInputWithoutScreenChangeKeepsContact() {
        val tracker = MoshContactTracker()
        tracker.isContactLost(pendingOutbound = 0, nowMs = 0)
        tracker.isContactLost(pendingOutbound = 1, nowMs = 100)
        tracker.isContactLost(pendingOutbound = 0, nowMs = 200)

        assertFalse(tracker.isContactLost(pendingOutbound = 0, nowMs = 60_000))
    }

    @Test
    fun moshIdleSessionKeepsContact() {
        val tracker = MoshContactTracker()

        assertFalse(tracker.isContactLost(pendingOutbound = 0, nowMs = 0))
        assertFalse(tracker.isContactLost(pendingOutbound = 0, nowMs = 60_000))
    }

    @Test
    fun moshResetForgetsAnOutstandingWindow() {
        val tracker = MoshContactTracker()
        tracker.isContactLost(pendingOutbound = 0, nowMs = 0)
        tracker.isContactLost(pendingOutbound = 1, nowMs = 100)
        tracker.reset()

        assertFalse(tracker.isContactLost(pendingOutbound = 1, nowMs = 10_000))
        assertFalse(tracker.isContactLost(pendingOutbound = 1, nowMs = 12_999))
        assertTrue(tracker.isContactLost(pendingOutbound = 1, nowMs = 13_000))
    }
}
