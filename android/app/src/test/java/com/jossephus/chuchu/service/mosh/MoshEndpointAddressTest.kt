package com.jossephus.chuchu.service.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshEndpointAddressTest {
    @Test
    fun usesTheIpv4AddressSshConnectedTo() {
        assertEquals(
            "100.72.23.13",
            MoshEndpointAddress.fromSshPeer("vps.tail36346.ts.net", "100.72.23.13"),
        )
    }

    @Test
    fun unwrapsAnIpv4MappedPeer() {
        assertEquals(
            "203.0.113.7",
            MoshEndpointAddress.fromSshPeer("vps.example", "::ffff:203.0.113.7"),
        )
    }

    @Test
    fun rejectsAnIpv6PeerNamingTheHost() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                MoshEndpointAddress.fromSshPeer("vps.example", "2001:db8::13")
            }

        assertTrue(error.message.orEmpty().contains("vps.example"))
        assertTrue(error.message.orEmpty().contains("2001:db8::13"))
    }

    @Test
    fun rejectsAScopedIpv6PeerWithoutTheZone() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                MoshEndpointAddress.fromSshPeer("printer.local", "fe80::1%wlan0")
            }

        assertTrue(error.message.orEmpty().contains("(fe80::1)"))
    }
}
