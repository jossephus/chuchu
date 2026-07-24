package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport
import org.junit.Assert.assertEquals
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
}
