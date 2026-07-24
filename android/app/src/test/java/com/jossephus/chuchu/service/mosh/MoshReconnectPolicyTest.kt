package com.jossephus.chuchu.service.mosh

import org.junit.Assert.assertTrue
import org.junit.Test

class MoshReconnectPolicyTest {
    @Test
    fun serverExpiresBeforeClientAllowsReplacementSession() {
        val serverTimeoutMs = MoshReconnectPolicy.SERVER_NETWORK_TIMEOUT_SECONDS * 1_000

        assertTrue(MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS > serverTimeoutMs)
        assertTrue(MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS <= 0xFFFF_FFFFL)
    }

    @Test
    fun retransmitBudgetOutlivesClientTimeout() {
        assertTrue(
            MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT *
                MoshReconnectPolicy.CLIENT_MIN_RETRANSMIT_INTERVAL_MS >
                MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS,
        )
        assertTrue(MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT <= 0xFFFF_FFFFL)
    }

    @Test
    fun bootstrapAppliesServerNetworkTimeout() {
        val command = MoshReconnectPolicy.bootstrapCommand()

        assertTrue(
            command.contains(
                "MOSH_SERVER_NETWORK_TMOUT=${MoshReconnectPolicy.SERVER_NETWORK_TIMEOUT_SECONDS}",
            ),
        )
        assertTrue(command.endsWith("mosh-server new -s -c 256"))
    }
}
