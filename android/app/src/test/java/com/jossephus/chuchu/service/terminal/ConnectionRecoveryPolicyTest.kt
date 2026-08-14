package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.mosh.MoshRuntimeState
import com.jossephus.chuchu.service.mosh.MoshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun moshStatesUseTheExistingSessionStatusIndicator() {
        assertEquals(
            SessionStatus.Disconnected,
            sessionStatusForMoshState(MoshState.Idle.code),
        )
        assertEquals(
            SessionStatus.Connecting,
            sessionStatusForMoshState(MoshState.Connecting.code),
        )
        assertEquals(
            SessionStatus.Connected,
            sessionStatusForMoshState(MoshState.Connected.code),
        )
        assertEquals(
            SessionStatus.Reconnecting,
            sessionStatusForMoshState(MoshState.Reconnecting.code),
        )
        assertEquals(
            SessionStatus.Disconnected,
            sessionStatusForMoshState(MoshState.Disconnecting.code),
        )
        assertEquals(
            SessionStatus.Error,
            sessionStatusForMoshState(MoshState.Failed.code),
        )
    }

    @Test
    fun moshStalenessReturnsConnectedWhenTheServerAcknowledgesNewState() {
        val tracker = MoshConnectionStalenessTracker()
        tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 0)

        assertEquals(
            SessionStatus.Connected,
            tracker.observe(moshRuntimeState(sent = 2, received = 2), nowMs = 1_000),
        )
    }

    @Test
    fun moshStalenessReturnsReconnectingWhenOutboundStateStopsBeingAcknowledged() {
        val tracker = MoshConnectionStalenessTracker()
        tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 0)
        tracker.observe(moshRuntimeState(sent = 2, received = 1, pendingOutbound = 1), nowMs = 1)

        assertEquals(
            SessionStatus.Reconnecting,
            tracker.observe(
                moshRuntimeState(sent = 2, received = 1, pendingOutbound = 1),
                nowMs = 3_001,
            ),
        )
        assertEquals(
            SessionStatus.Connected,
            tracker.observe(moshRuntimeState(sent = 2, received = 2), nowMs = 3_002),
        )
    }

    @Test
    fun moshStalenessLeavesAnIdleSessionUnchanged() {
        val tracker = MoshConnectionStalenessTracker()
        tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 0)

        assertNull(
            tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 3_001),
        )
    }

    private fun moshRuntimeState(
        sent: Long,
        received: Long,
        pendingOutbound: Int = 0,
    ): MoshRuntimeState =
        MoshRuntimeState(
            state = MoshState.Connected.code,
            lastFailureCode = 0,
            lastStateNumSent = sent,
            lastStateNumReceived = received,
            pendingOutbound = pendingOutbound,
            pendingHostOps = 0,
            currentRtoMs = 0,
        )
}
