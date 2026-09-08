package com.jossephus.chuchu.service.mosh

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshEndpointResolverTest {
    @Test
    fun preservesNumericIpv4Literal() {
        assertEquals("100.72.23.13", MoshEndpointResolver.resolve("100.72.23.13"))
    }

    @Test
    fun resolvesDnsNameToIpv4Literal() {
        val address = InetAddress.getByName("100.72.23.13")

        val result =
            MoshEndpointResolver.resolve("solom-vps.tail36346.ts.net") { arrayOf(address) }

        assertEquals("100.72.23.13", result)
    }

    @Test
    fun preservesIpv6AsAnUnbracketedConfigLiteral() {
        val address = InetAddress.getByName("2001:db8::13")

        val result = MoshEndpointResolver.resolve("vps.example") { arrayOf(address) }

        assertEquals(address.hostAddress, result)
        assertTrue(!result.startsWith("["))
        assertTrue(!result.endsWith("]"))
    }

    @Test
    fun namesHostWhenResolutionFails() {
        val error =
            try {
                MoshEndpointResolver.resolve("missing.example") {
                    throw UnknownHostException("missing.example")
                }
                throw AssertionError("Expected resolution to fail")
            } catch (error: IllegalStateException) {
                error
            }

        assertTrue(error.message.orEmpty().contains("missing.example"))
    }
}
